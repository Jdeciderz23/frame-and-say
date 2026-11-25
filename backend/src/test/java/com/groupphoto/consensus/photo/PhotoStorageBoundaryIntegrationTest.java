package com.groupphoto.consensus.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.bootstrap.organizer-token=storage_organizer_integration_test_only",
        "app.bootstrap.participant-token=storage_participant_integration_test_only"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoStorageBoundaryIntegrationTest {
    private static final String ORGANIZER = "Bearer storage_organizer_integration_test_only";
    private static final String PARTICIPANT = "Bearer storage_participant_integration_test_only";

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void useTemporaryStorage(DynamicPropertyRegistry properties) {
        properties.add("app.photo.root", () -> root.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean LocalPhotoStorage storage;

    @Test
    void failedWriteReturns503WithoutAFileOrCatalogEntry() throws Exception {
        String eventId = event("Unavailable storage", false);
        Set<Path> before = storedVersions();
        doThrow(new IOException("Storage failed: " + root.resolve("private-sentinel")))
                .when(storage).save(any(UUID.class), any(byte[].class), any(byte[].class));

        mvc.perform(multipart(photos(eventId)).file(image(4, 2)).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Photo storage is unavailable"))
                .andExpect(content().string(not(containsString("private-sentinel"))));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photos WHERE event_id = ?", Integer.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_versions WHERE event_id = ?", Integer.class, eventId)).isZero();
        assertThat(storedVersions()).isEqualTo(before);
    }

    @Test
    void catalogRejectionRemovesFilesWrittenBeforeTheQuotaCheck() throws Exception {
        String eventId = event("Full activity", false);
        for (int i = 0; i < 100; i++) {
            jdbc.update("INSERT INTO photos (id, event_id) VALUES (?, ?)", UUID.randomUUID().toString(), eventId);
        }
        Set<Path> before = storedVersions();

        mvc.perform(multipart(photos(eventId)).file(image(4, 2)).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isConflict());
        assertThat(storedVersions()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photos WHERE event_id = ?", Integer.class, eventId)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_versions WHERE event_id = ?", Integer.class, eventId)).isZero();
    }

    @Test
    void missingThumbnailReturns503OnlyAfterMembershipAndVersionChecks() throws Exception {
        String eventId = event("Missing thumbnail", false);
        String other = event("Other activity", true);
        var uploaded = upload(eventId);
        String path = uploaded.required("thumbnailUrl").asText();
        String versionId = uploaded.required("photoVersionId").asText();
        byte[] original = Files.readAllBytes(root.resolve(versionId).resolve("original"));
        Files.delete(root.resolve(versionId).resolve("thumbnail.png"));

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, PARTICIPANT)).andExpect(status().isForbidden());
        mvc.perform(get(path.replace(eventId, other)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isNotFound());
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Photo storage is unavailable"))
                .andExpect(content().string(not(containsString("thumbnail.png"))));
        assertThat(Files.readAllBytes(root.resolve(versionId).resolve("original"))).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_versions WHERE id = ?", Integer.class, versionId)).isOne();
    }

    @Test
    void latestVersionIsListedButBothThumbnailsAreBoundToTheirExactPhotoAndActivity() throws Exception {
        String eventId = event("Photo versions", true);
        String otherEventId = event("Separate album", true);
        var first = upload(eventId);
        String photoId = first.required("photoId").asText();
        String firstVersion = first.required("photoVersionId").asText();
        String firstPath = first.required("thumbnailUrl").asText();
        String otherPhotoId = upload(eventId).required("photoId").asText();
        byte[] firstOriginal = Files.readAllBytes(root.resolve(firstVersion).resolve("original"));
        byte[] firstThumb = thumbnail(firstPath);
        mvc.perform(put(firstPath.replace("/thumbnail", "/subjects")).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subjectRevision\":0,\"userIds\":[\"participant\"]}"))
                .andExpect(status().isOk());

        // The version creation endpoint is scoped to US-21. Seed V2 via the storage/catalog boundary.
        UUID secondVersion = UUID.randomUUID();
        byte[] secondOriginal = image(2, 4).getBytes();
        byte[] secondThumb = image(2, 4).getBytes();
        storage.save(secondVersion, secondOriginal, secondThumb);
        jdbc.update("""
                INSERT INTO photo_versions (id, photo_id, event_id, version_number, media_type)
                VALUES (?, ?, ?, 2, 'image/png')
                """, secondVersion.toString(), photoId, eventId);
        String secondPath = firstPath.replace(firstVersion, secondVersion.toString());

        var list = json.readTree(mvc.perform(get(photos(eventId)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(2);
        JsonNode latest = null;
        for (JsonNode photo : list) {
            if (photo.required("photoId").asText().equals(photoId)) latest = photo;
        }
        assertThat(latest).isNotNull();
        assertThat(latest.required("photoVersionId").asText()).isEqualTo(secondVersion.toString());
        assertThat(latest.required("subjectRevision").asLong()).isZero();
        assertThat(latest.required("rosterConfirmed").asBoolean()).isFalse();
        assertThat(latest.required("thumbnailUrl").asText()).isEqualTo(secondPath);
        assertThat(thumbnail(firstPath)).isEqualTo(firstThumb);
        assertThat(thumbnail(secondPath)).isEqualTo(secondThumb).isNotEqualTo(firstThumb);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnail(secondPath)));
        assertThat(decoded.getWidth()).isEqualTo(2);
        assertThat(decoded.getHeight()).isEqualTo(4);
        assertThat(Files.readAllBytes(root.resolve(firstVersion).resolve("original"))).isEqualTo(firstOriginal);
        assertThat(Files.readAllBytes(root.resolve(secondVersion.toString()).resolve("original"))).isEqualTo(secondOriginal);
        mvc.perform(get(secondPath.replace(photoId, otherPhotoId)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isNotFound());
        mvc.perform(get(secondPath.replace(eventId, otherEventId)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isNotFound());
    }

    private byte[] thumbnail(String path) throws Exception {
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private Set<Path> storedVersions() throws IOException {
        try (var paths = Files.list(root)) {
            return paths.collect(Collectors.toSet());
        }
    }

    private JsonNode upload(String eventId) throws Exception {
        return json.readTree(mvc.perform(multipart(photos(eventId)).file(image(4, 2))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private String event(String name, boolean addParticipant) throws Exception {
        String response = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", name).toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).required("id").asText();
        if (addParticipant) {
            mvc.perform(post("/api/events/{id}/members", id).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                    .andExpect(status().isNoContent());
        }
        return id;
    }

    private static String photos(String eventId) {
        return "/api/events/" + eventId + "/photos";
    }

    private static MockMultipartFile image(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x80a5e0);
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            assertThat(ImageIO.write(image, "png", stream)).isTrue();
        }
        return new MockMultipartFile("file", "synthetic.png", "image/png", bytes.toByteArray());
    }
}
