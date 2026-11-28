package com.groupphoto.consensus.photo;

import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER;
import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER_PROPERTY;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {ORGANIZER_PROPERTY, PARTICIPANT_PROPERTY})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewedPhotoExportIntegrationTest {
    private static final String INTERNAL = "INTERNAL_SHARE";
    private static final String PUBLIC = "PUBLIC_POST";

    @TempDir
    static Path root;

    @DynamicPropertySource
    static void temporaryStorage(DynamicPropertyRegistry registry) {
        registry.add("app.photo.root", () -> root.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LocalPhotoStorage storage;

    @Test
    void deliversOnlyExactReviewedFilesThenRejectsTheWholeZipAfterRevocation() throws Exception {
        String event = event();
        Photo first = upload(event, "png", 12);
        Photo second = upload(event, "jpeg", 21);
        upload(event, "png", 40); // This photo is outside the confirmed list.
        for (Photo photo : List.of(first, second)) {
            roster(photo, 0, "participant");
            decide(photo, INTERNAL, "ALLOW");
            decide(photo, PUBLIC, "ALLOW");
        }
        String download = confirm(event, first, second);

        byte[] zip = mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION))
                .andReturn().getResponse().getContentAsByteArray();
        var entries = new LinkedHashMap<String, byte[]>();
        try (var stream = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                assertThat(entries.put(entry.getName(), stream.readAllBytes())).isNull();
            }
        }
        assertThat(entries.keySet()).containsExactly(first.versionId() + ".png", second.versionId() + ".jpg");
        assertThat(entries.get(first.versionId() + ".png")).isEqualTo(first.bytes());
        assertThat(entries.get(second.versionId() + ".jpg")).isEqualTo(second.bytes());

        decide(second, INTERNAL, "DENY");
        assertInvalidated(mvc.perform(get(download).param("purpose", PUBLIC).header(HttpHeaders.AUTHORIZATION, ORGANIZER)));
    }

    @Test
    void preservesConfirmedVersionsAcrossRealUploadsAndFiltersTheMemberView() throws Exception {
        String event = event();
        String selections = "/api/events/" + event + "/confirmed-selections";
        Photo first = upload(event, "png", 12);
        Photo second = upload(event, "png", 20);
        roster(first, 0, "participant");
        decide(first, INTERNAL, "ALLOW");
        roster(second, 0);
        String download = confirm(event, second, first);
        var confirmed = json.readTree(mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].purpose").value(INTERNAL))
                .andExpect(jsonPath("$[0].items[0].photoVersionId").value(second.versionId()))
                .andExpect(jsonPath("$[0].items[1].photoVersionId").value(first.versionId()))
                .andReturn().getResponse().getContentAsString()).get(0);
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].items.length()").value(2));

        Photo edited = uploadVersion(first);
        roster(edited, 0, "participant");
        decide(edited, INTERNAL, "ALLOW");
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(content().json("[" + confirmed + "]", JsonCompareMode.STRICT));
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].items.length()").value(1))
                .andExpect(jsonPath("$[0].items[0].photoVersionId").value(second.versionId()));
        assertInvalidated(mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, ORGANIZER)));

        String next = confirm(event, second, edited);
        assertThat(next).isNotEqualTo(download);
        mvc.perform(get(next).header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isOk());
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].items[*].photoVersionId", hasItem(edited.versionId())));
    }

    @Test
    void limitsVerificationToTheEventAndCurrentlyPermittedPurposeAndRoster() throws Exception {
        String event = event();
        String selections = "/api/events/" + event + "/confirmed-selections";
        Photo photo = upload(event, "png", 12);
        roster(photo, 0, "participant");
        decide(photo, INTERNAL, "ALLOW");
        decide(photo, PUBLIC, "ALLOW");
        submitConfirmation(event, PARTICIPANT, photo).andExpect(status().isForbidden());
        confirm(event, photo);
        decide(photo, INTERNAL, "DENY");
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        decide(photo, INTERNAL, "ALLOW");
        roster(photo, 1);
        mvc.perform(get(selections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(selections)).andExpect(status().isUnauthorized());
        String otherSelections = "/api/events/" + organizerOnlyEvent() + "/confirmed-selections";
        mvc.perform(get(otherSelections).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(otherSelections).header(HttpHeaders.AUTHORIZATION, PARTICIPANT)).andExpect(status().isForbidden());
    }

    @Test
    void rechecksAllVersionsAfterPreparingTheZip() throws Exception {
        String event = event();
        Photo first = upload(event, "png", 12);
        Photo second = upload(event, "png", 20);
        for (Photo photo : List.of(first, second)) {
            roster(photo, 0, "participant");
            decide(photo, INTERNAL, "ALLOW");
        }
        String download = confirm(event, first, second);
        doAnswer(call -> {
            decide(first, INTERNAL, "DENY");
            return call.callRealMethod();
        }).when(storage).openOriginal(UUID.fromString(second.versionId()));

        assertInvalidated(mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, ORGANIZER)));
    }

    @Test
    void isolatesEventsAndRejectsAChangedRosterOrVersion() throws Exception {
        String event = event();
        Photo first = upload(event, "png", 12);
        Photo second = upload(event, "png", 20);
        roster(first, 0);
        roster(second, 0);
        String download = confirm(event, first, second);
        mvc.perform(get(download)).andExpect(status().isUnauthorized());
        mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
        String otherEvent = event();
        mvc.perform(get(download.replace(event, otherEvent)).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));

        uploadVersion(second);
        assertInvalidated(mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, ORGANIZER)));

        String rosterOnly = confirm(event, first);
        roster(first, 1, "participant");
        mvc.perform(put(first.resource() + "/consents/me").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON).content(json.createObjectNode()
                                .put("photoVersionId", first.versionId()).put("subjectRevision", 2)
                                .put("purpose", INTERNAL).put("decision", "ALLOW").toString()))
                .andExpect(status().isNoContent());
        assertInvalidated(mvc.perform(get(rosterOnly).header(HttpHeaders.AUTHORIZATION, ORGANIZER)));
    }

    @Test
    void rejectsUnconfirmedOrDuplicateItemsAndDoesNotPublishPartialArchives() throws Exception {
        String event = event();
        Photo first = upload(event, "png", 12);
        Photo second = upload(event, "png", 20);
        roster(first, 0);
        submitConfirmation(event, first, second).andExpect(status().isConflict());
        submitConfirmation(event, first, first).andExpect(status().isBadRequest());
        roster(second, 0, "participant");
        submitConfirmation(event, first, second).andExpect(status().isConflict()); // Pending.
        decide(second, INTERNAL, "ALLOW");
        String download = confirm(event, first, second);
        Files.delete(root.resolve(second.versionId()).resolve("original"));
        mvc.perform(get(download).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }

    private void assertInvalidated(ResultActions result) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(jsonPath("$.detail").value("The confirmed selection is no longer valid; review it again"));
    }

    private String organizerOnlyEvent() throws Exception {
        String body = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Reviewed export\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).required("id").asText();
    }

    private String event() throws Exception {
        String event = organizerOnlyEvent();
        mvc.perform(post("/api/events/{id}/members", event).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                .andExpect(status().isNoContent());
        return event;
    }

    private Photo upload(String event, String format, int width) throws Exception {
        byte[] bytes = SyntheticImages.image(format, width, 12);
        String path = "/api/events/" + event + "/photos";
        var file = new MockMultipartFile("file", "synthetic." + format, "image/" + format, bytes);
        String body = mvc.perform(multipart(path).file(file).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var payload = json.readTree(body);
        String photo = payload.required("photoId").asText();
        return new Photo(path + "/" + photo, photo, payload.required("photoVersionId").asText(), bytes);
    }

    private Photo uploadVersion(Photo base) throws Exception {
        byte[] bytes = SyntheticImages.image("png", 30, 12);
        var file = new MockMultipartFile("file", "edit.png", "image/png", bytes);
        String response = mvc.perform(multipart(base.resource() + "/versions").file(file)
                        .param("baseVersionId", base.versionId()).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String version = json.readTree(response).required("photo").required("photoVersionId").asText();
        return new Photo(base.resource(), base.photoId(), version, bytes);
    }

    private void roster(Photo photo, long revision, String... users) throws Exception {
        var body = json.createObjectNode().put("subjectRevision", revision);
        var roster = body.putArray("userIds");
        for (String user : users) roster.add(user);
        mvc.perform(put(photo.resource() + "/versions/" + photo.versionId() + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER).contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk());
    }

    private void decide(Photo photo, String purpose, String decision) throws Exception {
        var body = json.createObjectNode().put("photoVersionId", photo.versionId()).put("subjectRevision", 1)
                .put("purpose", purpose).put("decision", decision);
        mvc.perform(put(photo.resource() + "/consents/me").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isNoContent());
    }

    private ResultActions submitConfirmation(String event, Photo... photos) throws Exception {
        return submitConfirmation(event, ORGANIZER, photos);
    }

    private ResultActions submitConfirmation(String event, String actor, Photo... photos) throws Exception {
        var body = json.createObjectNode().put("purpose", INTERNAL);
        var items = body.putArray("items");
        for (Photo photo : photos) {
            items.addObject().put("photoId", photo.photoId()).put("photoVersionId", photo.versionId()).put("subjectRevision", 1);
        }
        return mvc.perform(post("/api/events/" + event + "/confirmed-selections").header(HttpHeaders.AUTHORIZATION, actor)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private String confirm(String event, Photo... photos) throws Exception {
        var body = submitConfirmation(event, photos).andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value(INTERNAL))
                .andReturn().getResponse().getContentAsString();
        return "/api/events/" + event + "/confirmed-selections/" + json.readTree(body).required("selectionId").asText() + "/export";
    }

    private record Photo(String resource, String photoId, String versionId, byte[] bytes) {}
}
