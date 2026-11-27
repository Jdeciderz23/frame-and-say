package com.groupphoto.consensus.photo;

import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER;
import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER_PROPERTY;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT_PROPERTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.doAnswer;
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
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {ORGANIZER_PROPERTY, PARTICIPANT_PROPERTY})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoDeletionIntegrationTest {
    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void configureStorage(DynamicPropertyRegistry registry) {
        registry.add("app.photo.root", () -> storageRoot.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean LocalPhotoStorage storage;

    @Test
    void approvalHidesAllVersionsAndRejectsTheConfirmedExport() throws Exception {
        String event = event(true);
        JsonNode original = upload(event, PARTICIPANT, 18);
        String photoId = original.required("photoId").asText();
        String resource = photos(event) + "/" + photoId;
        JsonNode latest = json.readTree(mvc.perform(multipart(resource + "/versions").file(image(25))
                        .param("baseVersionId", original.required("photoVersionId").asText())
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).required("photo");
        JsonNode other = upload(event, ORGANIZER, 30);
        confirmEmptyRoster(event, latest);
        confirmEmptyRoster(event, other);
        String export = confirmSelection(event, latest, other);
        String request = resource + "/deletion-request";
        String progress = "/api/events/" + event + "/consent-progress?purpose=INTERNAL_SHARE";
        String remainingVersion = other.required("photoVersionId").asText();
        String entry = submit(request, PARTICIPANT);
        assertThat(submit(request, PARTICIPANT)).isEqualTo(entry);
        mvc.perform(get(request).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestedBy").value("participant"));
        for (JsonNode version : new JsonNode[]{original, latest}) {
            mvc.perform(get(version.required("thumbnailUrl").asText()).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));
            mvc.perform(get(legacyMetadata(event, version)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isOk());
        }
        mvc.perform(get(progress).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].photoVersionId",
                        containsInAnyOrder(latest.required("photoVersionId").asText(), remainingVersion)));
        mvc.perform(get(export).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(content().contentType("application/zip"));

        String result = approve(request);
        assertThat(approve(request)).isEqualTo(result);
        assertThat(submit(request, PARTICIPANT)).isEqualTo(result);
        mvc.perform(get(request).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(content().json(result));
        mvc.perform(get("/api/events/" + event + "/photo-deletion-requests").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].processedBy").value("organizer"))
                .andExpect(jsonPath("$[0].processedAt").isString());

        for (JsonNode version : new JsonNode[]{original, latest}) {
            mvc.perform(get(version.required("thumbnailUrl").asText()).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
            mvc.perform(get(legacyMetadata(event, version)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get(resource + "/versions").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isNotFound());
        mvc.perform(get(photos(event)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].photoId").value(other.required("photoId").asText()));
        mvc.perform(get(progress).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].photoId").value(other.required("photoId").asText()))
                .andExpect(jsonPath("$[0].photoVersionId").value(remainingVersion))
                .andExpect(jsonPath("$[0].eligible").value(true));
        assertInvalidExport(export);

        byte[] remaining = mvc.perform(get(confirmSelection(event, other)).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(remaining))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo(remainingVersion + ".png");
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void onlyRecordedUploaderRequestsAndSameEventOrganizerApproves() throws Exception {
        String event = event(true);
        String foreign = event(false);
        var photo = upload(event, PARTICIPANT, 20);
        String id = photo.required("photoId").asText();
        String request = photos(event) + "/" + id + "/deletion-request";
        mvc.perform(put(request + "/approval").header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isNotFound());
        mvc.perform(post(request)).andExpect(status().isUnauthorized());
        mvc.perform(post(request).header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isForbidden());
        submit(request, PARTICIPANT);
        mvc.perform(put(request + "/approval").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)).andExpect(status().isForbidden());
        mvc.perform(put(request.replace(event, foreign) + "/approval").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isNotFound());
        mvc.perform(get(request.replace(event, foreign)).header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/events/" + event + "/photo-deletion-requests").header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        mvc.perform(get(request).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void approvalWhileZipIsBeingPreparedPreventsDelivery() throws Exception {
        String event = event(true);
        JsonNode photo = upload(event, PARTICIPANT, 20);
        confirmEmptyRoster(event, photo);
        String export = confirmSelection(event, photo);
        String request = photos(event) + "/" + photo.required("photoId").asText() + "/deletion-request";
        submit(request, PARTICIPANT);
        doAnswer(call -> {
            approve(request);
            return call.callRealMethod();
        }).when(storage).openOriginal(UUID.fromString(photo.required("photoVersionId").asText()));

        assertInvalidExport(export);
    }

    private void assertInvalidExport(String path) throws Exception {
        mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));
    }

    private String submit(String request, String token) throws Exception {
        return mvc.perform(post(request).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String approve(String request) throws Exception {
        return mvc.perform(put(request + "/approval").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
    }

    private void confirmEmptyRoster(String event, JsonNode photo) throws Exception {
        String subjects = photos(event) + "/" + photo.required("photoId").asText()
                + "/versions/" + photo.required("photoVersionId").asText() + "/subjects";
        mvc.perform(put(subjects).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subjectRevision\":0,\"userIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjectRevision").value(1));
    }

    private String confirmSelection(String event, JsonNode... items) throws Exception {
        var body = json.createObjectNode().put("purpose", "INTERNAL_SHARE");
        var picked = body.putArray("items");
        for (JsonNode item : items) {
            picked.addObject().put("photoId", item.required("photoId").asText())
                    .put("photoVersionId", item.required("photoVersionId").asText()).put("subjectRevision", 1);
        }
        String root = "/api/events/" + event + "/confirmed-selections";
        String response = mvc.perform(post(root).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return root + "/" + json.readTree(response).required("selectionId").asText() + "/export";
    }

    private String event(boolean addParticipant) throws Exception {
        String response = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Photo deletion\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String event = json.readTree(response).required("id").asText();
        if (addParticipant) {
            mvc.perform(post("/api/events/{id}/members", event).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                    .andExpect(status().isNoContent());
        }
        return event;
    }

    private JsonNode upload(String event, String token, int width) throws Exception {
        String response = mvc.perform(multipart(photos(event)).file(image(width)).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static MockMultipartFile image(int width) throws Exception {
        return new MockMultipartFile("file", "synthetic.png", "image/png", SyntheticImages.image("png", width, 12));
    }

    private static String photos(String event) {
        return "/api/events/" + event + "/photos";
    }

    private static String legacyMetadata(String event, JsonNode version) {
        return photos(event) + "/versions/" + version.required("photoVersionId").asText() + "/metadata";
    }
}
