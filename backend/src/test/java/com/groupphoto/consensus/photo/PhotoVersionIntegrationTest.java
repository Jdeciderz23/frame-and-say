package com.groupphoto.consensus.photo;

import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER;
import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER_PROPERTY;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {ORGANIZER_PROPERTY, PARTICIPANT_PROPERTY})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoVersionIntegrationTest {
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void useTestStorage(DynamicPropertyRegistry properties) {
        properties.add("app.photo.root", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LocalPhotoStorage storage;

    @Test
    void savesAnotherVersionWithoutInheritingFilesRosterOrConsent() throws Exception {
        String event = createEvent(true);
        byte[] original = SyntheticImages.image("png", 80, 40);
        byte[] cropped = SyntheticImages.image("png", 32, 32);
        JsonNode v1 = upload(event, PARTICIPANT, original);
        String photo = v1.required("photoId").asText();
        String base = v1.required("photoVersionId").asText();
        String oldThumbnail = v1.required("thumbnailUrl").asText();
        String versions = photos(event) + "/" + photo + "/versions";
        String consents = photos(event) + "/" + photo + "/consents/me";
        confirmRoster(versions, base);
        mvc.perform(put(consents).header(HttpHeaders.AUTHORIZATION, PARTICIPANT).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("photoVersionId", base).put("subjectRevision", 1)
                                .put("purpose", "INTERNAL_SHARE").put("decision", "ALLOW").toString()))
                .andExpect(status().isNoContent());

        mvc.perform(get(versions).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCreateVersion").value(true));
        JsonNode v2 = json.readTree(save(versions, base, PARTICIPANT, cropped)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNumber").value(2))
                .andExpect(jsonPath("$.photo.photoId").value(photo))
                .andExpect(jsonPath("$.photo.subjectRevision").value(0))
                .andExpect(jsonPath("$.photo.rosterConfirmed").value(false))
                .andReturn().getResponse().getContentAsString()).required("photo");
        String current = v2.required("photoVersionId").asText();
        assertThat(current).isNotEqualTo(base);
        assertThat(Files.readAllBytes(storageRoot.resolve(base).resolve("original"))).isEqualTo(original);
        assertThat(Files.readAllBytes(storageRoot.resolve(current).resolve("original"))).isEqualTo(cropped);
        assertThat(SyntheticImages.decode(thumbnail(oldThumbnail)).getWidth()).isEqualTo(80);
        assertThat(SyntheticImages.decode(thumbnail(v2.required("thumbnailUrl").asText())).getWidth()).isEqualTo(32);
        mvc.perform(get(versions).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].photo.photoVersionId").value(current))
                .andExpect(jsonPath("$.versions[1].photo.photoVersionId").value(base))
                .andExpect(jsonPath("$.versions[1].photo.rosterConfirmed").value(true));
        String lookupSuffix = "/versions/" + base + "/metadata";
        String legacyLookup = photos(event) + lookupSuffix;
        mvc.perform(get(legacyLookup).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionNumber").value(1))
                .andExpect(jsonPath("$.photo.photoId").value(photo))
                .andExpect(jsonPath("$.photo.photoVersionId").value(base))
                .andExpect(jsonPath("$.photo.thumbnailUrl").value(oldThumbnail));
        mvc.perform(get(legacyLookup)).andExpect(status().isUnauthorized());
        String separateEvent = createEvent(false);
        mvc.perform(get(photos(separateEvent) + lookupSuffix).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        mvc.perform(get(photos(separateEvent) + lookupSuffix).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isNotFound());
        mvc.perform(get(photos(event)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].photoVersionId").value(current));
        readConsent(consents, base).andExpect(status().isConflict());
        readConsent(consents, current).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PENDING")).andExpect(jsonPath("$.canDecide").value(false));
        confirmRoster(versions, current);
        readConsent(consents, current).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PENDING")).andExpect(jsonPath("$.canDecide").value(true));
        assertThat(jdbc.queryForObject("SELECT decision FROM photo_consents WHERE photo_version_id = ?", String.class, base))
                .isEqualTo("ALLOW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_consents WHERE photo_version_id = ?", Integer.class, current))
                .isZero();
    }

    @Test
    void enforcesUploaderOrOrganizerAndRetainsLegacyReadAccess() throws Exception {
        String event = createEvent(true);
        byte[] png = SyntheticImages.image("png", 16, 16);
        JsonNode first = upload(event, ORGANIZER, png);
        String photo = first.required("photoId").asText();
        String version = first.required("photoVersionId").asText();
        String path = photos(event) + "/" + photo + "/versions";
        String separateEvent = createEvent(true);
        String outsiderEvent = createEvent(false);
        JsonNode other = upload(outsiderEvent, ORGANIZER, png);
        String otherPath = photos(outsiderEvent) + "/" + other.required("photoId").asText() + "/versions";

        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCreateVersion").value(false));
        save(path, version, PARTICIPANT, png).andExpect(status().isForbidden());
        save(otherPath, other.required("photoVersionId").asText(), PARTICIPANT, png).andExpect(status().isForbidden());
        save(photos(separateEvent) + "/" + photo + "/versions", version, ORGANIZER, png).andExpect(status().isNotFound());
        save(path, other.required("photoVersionId").asText(), ORGANIZER, png).andExpect(status().isNotFound());
        save(path, version, ORGANIZER, new byte[]{1, 2, 3}).andExpect(status().isUnsupportedMediaType());

        // Pre-migration rows have no recorded uploader.
        jdbc.update("UPDATE photos SET uploader_id = NULL WHERE id = ?", photo);
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canCreateVersion").value(false));
        save(path, version, PARTICIPANT, png).andExpect(status().isForbidden());
        save(path, version, ORGANIZER, png).andExpect(status().isCreated());
        save(path, version, ORGANIZER, png).andExpect(status().isConflict());
    }

    @Test
    void onlyOneConcurrentSaveFromTheSameBaseSurvives() throws Exception {
        String event = createEvent(true);
        byte[] png = SyntheticImages.image("png", 16, 16);
        var v1 = upload(event, PARTICIPANT, png);
        String photo = v1.required("photoId").asText();
        String base = v1.required("photoVersionId").asText();
        String path = photos(event) + "/" + photo + "/versions";
        Set<String> before = storedIds();
        var persistedBoth = new CountDownLatch(2);
        doAnswer(call -> {
            call.callRealMethod();
            persistedBoth.countDown();
            assertThat(persistedBoth.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(storage).save(any(UUID.class), any(byte[].class), any(byte[].class));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> save(path, base, PARTICIPANT, png).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> save(path, base, ORGANIZER, png).andReturn().getResponse().getStatus());
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        var added = storedIds();
        added.removeAll(before);
        assertThat(added).hasSize(1);
        assertThat(jdbc.queryForList("SELECT id FROM photo_versions WHERE photo_id = ? AND version_number = 2", String.class, photo))
                .containsExactlyElementsOf(added);
    }

    private Set<String> storedIds() throws Exception {
        try (var paths = Files.list(storageRoot)) {
            return paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private ResultActions save(String target, String base, String token, byte[] bytes) throws Exception {
        return mvc.perform(multipart(target).file(file(bytes)).param("baseVersionId", base)
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private ResultActions readConsent(String target, String version) throws Exception {
        return mvc.perform(get(target).param("photoVersionId", version).param("purpose", "INTERNAL_SHARE")
                .header(HttpHeaders.AUTHORIZATION, PARTICIPANT));
    }

    private void confirmRoster(String versionsPath, String version) throws Exception {
        mvc.perform(put(versionsPath + "/" + version + "/subjects").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectRevision\":0,\"userIds\":[\"participant\"]}"))
                .andExpect(status().isOk());
    }

    private byte[] thumbnail(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        return mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private String createEvent(boolean addParticipant) throws Exception {
        String body = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Version test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String event = json.readTree(body).required("id").asText();
        if (addParticipant) {
            mvc.perform(post("/api/events/{id}/members", event).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                    .andExpect(status().isNoContent());
        }
        return event;
    }

    private JsonNode upload(String event, String token, byte[] bytes) throws Exception {
        return json.readTree(mvc.perform(multipart(photos(event)).file(file(bytes)).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "synthetic.png", "image/png", bytes);
    }

    private static String photos(String event) {
        return "/api/events/" + event + "/photos";
    }
}
