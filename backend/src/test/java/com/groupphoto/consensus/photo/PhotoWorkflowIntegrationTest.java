package com.groupphoto.consensus.photo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
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
import com.groupphoto.consensus.authorization.ConsentService;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.authorization.Publications.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "app.bootstrap.organizer-token=photo_organizer_integration_test_only",
        "app.bootstrap.participant-token=photo_participant_integration_test_only"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhotoWorkflowIntegrationTest {
    private static final String ORGANIZER = "Bearer photo_organizer_integration_test_only";
    private static final String PARTICIPANT = "Bearer photo_participant_integration_test_only";

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void photoDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.photo.root", () -> storage.toString());
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConsentService consents;

    @Test
    void participantCreatesOnlyOneInitialVersionAndProtectedThumbnail() throws Exception {
        String eventId = createEvent("Participant upload", true);
        var accepted = mvc.perform(multipart(photosAt(eventId)).file(image("png"))
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectRevision").value(0))
                .andExpect(jsonPath("$.rosterConfirmed").value(false))
                .andReturn().getResponse().getContentAsString();
        var uploaded = json.readTree(accepted);
        String photoId = uploaded.required("photoId").asText();
        String versionId = uploaded.required("photoVersionId").asText();
        String thumbnail = uploaded.required("thumbnailUrl").asText();
        var fake = new MockMultipartFile("file", "pretender.png", "image/png", "synthetic text".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(multipart(photosAt(eventId)).file(fake).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isUnsupportedMediaType());

        mvc.perform(get(photosAt(eventId)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].photoId").value(photoId))
                .andExpect(jsonPath("$[0].photoVersionId").value(versionId));
        assertThat(jdbc.queryForList("SELECT id FROM photos WHERE event_id = ?", String.class, eventId))
                .containsExactly(photoId);
        assertThat(jdbc.queryForList("SELECT id FROM photo_versions WHERE event_id = ?", String.class, eventId))
                .containsExactly(versionId);
        assertThat(jdbc.queryForObject("SELECT version_number FROM photo_versions WHERE id = ?", Integer.class, versionId))
                .isOne();
        assertThat(Files.isRegularFile(storage.resolve(versionId).resolve("original"))).isTrue();
        mvc.perform(get(thumbnail)).andExpect(status().isUnauthorized());
        var thumbnailBytes = mvc.perform(get(thumbnail).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        var decoded = SyntheticImages.decode(thumbnailBytes);
        assertThat(decoded.getWidth()).isEqualTo(320);
        assertThat(decoded.getHeight()).isEqualTo(160);
    }

    @ParameterizedTest
    @CsvSource({"jpeg, ../../same.png", "png, ../../same.jpg",
            "jpeg, C:\\uploads\\same.png", "png, C:\\uploads\\same.jpg"})
    void keepsUntrustedFilenamesOutOfStorageAndResponses(String format, String filename) throws Exception {
        String eventId = createEvent("Filename isolation", false);
        byte[] original = SyntheticImages.image(format, 640, 320);
        Set<String> versionIds = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            String response = mvc.perform(multipart(photosAt(eventId))
                            .file(new MockMultipartFile("file", filename, "text/plain", original))
                            .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            JsonNode metadata = json.readTree(response);
            assertMetadataFields(metadata);
            String versionId = metadata.required("photoVersionId").asText();
            assertThat(UUID.fromString(versionId).toString()).isEqualTo(versionId);
            assertThat(versionIds.add(versionId)).isTrue();
            Path directory = storage.resolve(versionId);
            assertThat(Files.readAllBytes(directory.resolve("original"))).isEqualTo(original);
            try (var files = Files.list(directory)) {
                assertThat(files.map(path -> path.getFileName().toString()))
                        .containsExactlyInAnyOrder("original", "thumbnail.png");
            }
            assertThat(jdbc.queryForObject("SELECT media_type FROM photo_versions WHERE id = ?",
                    String.class, versionId)).isEqualTo("image/" + format);

            String thumbnail = metadata.required("thumbnailUrl").asText();
            assertThat(thumbnail).isEqualTo(photosAt(eventId) + "/" + metadata.required("photoId").asText()
                    + "/versions/" + versionId + "/thumbnail");
            mvc.perform(get(thumbnail)).andExpect(status().isUnauthorized());
            mvc.perform(get(thumbnail).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isForbidden());
            byte[] served = mvc.perform(get(thumbnail).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.IMAGE_PNG))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(served).isEqualTo(Files.readAllBytes(directory.resolve("thumbnail.png")));
            var decoded = SyntheticImages.decode(served);
            assertThat(decoded.getWidth()).isEqualTo(320);
            assertThat(decoded.getHeight()).isEqualTo(160);
            for (String path : new String[]{"/data/photos/" + versionId + "/original",
                    "/data/photos/" + versionId + "/thumbnail.png",
                    photosAt(eventId) + "/" + metadata.required("photoId").asText()
                            + "/versions/" + versionId + "/original"}) {
                mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                        .andExpect(status().isNotFound());
            }
        }
        JsonNode listed = json.readTree(mvc.perform(get(photosAt(eventId))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(listed.size()).isEqualTo(2);
        Set<String> listedVersions = new HashSet<>();
        for (JsonNode metadata : listed) {
            assertMetadataFields(metadata);
            listedVersions.add(metadata.required("photoVersionId").asText());
        }
        assertThat(listedVersions).isEqualTo(versionIds);
    }

    @ParameterizedTest
    @CsvSource({"jpeg, empty, 400", "png, empty, 400", "jpeg, fake, 415", "png, fake, 415",
            "jpeg, bytes, 413", "png, bytes, 413", "jpeg, pixels, 413", "png, pixels, 413"})
    void rejectsInvalidUploadsWithoutLeavingFilesOrRows(String format, String scenario, int statusCode) throws Exception {
        String eventId = createEvent("Rejected upload", false);
        Set<Path> before;
        try (var paths = Files.list(storage)) {
            before = paths.collect(java.util.stream.Collectors.toSet());
        }
        byte[] bytes = switch (scenario) {
            case "empty" -> new byte[0];
            case "fake" -> new byte[]{1, 2, 3};
            case "bytes" -> new byte[8 * 1024 * 1024 + 1];
            case "pixels" -> SyntheticImages.image(format, 6000, 4001);
            default -> throw new IllegalArgumentException(scenario);
        };
        mvc.perform(multipart(photosAt(eventId))
                        .file(new MockMultipartFile("file", "invalid." + format, "image/" + format, bytes))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photos WHERE event_id = ?", Long.class, eventId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_versions WHERE event_id = ?", Long.class, eventId)).isZero();
        try (var paths = Files.list(storage)) {
            assertThat(paths.collect(java.util.stream.Collectors.toSet())).isEqualTo(before);
        }
    }

    private static void assertMetadataFields(JsonNode metadata) {
        Set<String> fields = new HashSet<>();
        metadata.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("photoId", "photoVersionId", "eventId", "thumbnailAvailable",
                "thumbnailUrl", "subjectRevision", "rosterConfirmed");
        assertThat(metadata.required("thumbnailAvailable").asBoolean()).isTrue();
    }

    @Test
    void validatesUploadAndServesThumbnailOnlyInItsMemberActivity() throws Exception {
        String eventId = createEvent("Storage", false);
        String photos = photosAt(eventId);
        mvc.perform(get(photos)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(photos).file(image("png"))
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(photos)
                        .file(new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1, 2, 3}))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart(photos)
                        .file(new MockMultipartFile("file", "big.png", "image/png", new byte[8 * 1024 * 1024 + 1]))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isPayloadTooLarge());

        JsonNode uploaded = upload(eventId, "jpeg");
        String thumbnail = uploaded.required("thumbnailUrl").asText();
        mvc.perform(get(thumbnail).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        String other = createEvent("Other activity", true);
        mvc.perform(get(thumbnail.replace(eventId, other)).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isNotFound());
        addParticipant(eventId);
        byte[] thumbnailBytes = mvc.perform(get(thumbnail).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(thumbnailBytes));
        assertThat(resized.getWidth()).isEqualTo(320);
        assertThat(resized.getHeight()).isEqualTo(160);
        mvc.perform(get(photos).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].photoVersionId").value(uploaded.required("photoVersionId").asText()))
                .andExpect(jsonPath("$[0].rosterConfirmed").value(false));
    }

    @Test
    void keepsPreferenceSeparateAndInvalidatesConsentOnRosterChange() throws Exception {
        String eventId = createEvent("People", true);
        JsonNode uploaded = upload(eventId, "png");
        String photoId = uploaded.required("photoId").asText();
        String versionId = uploaded.required("photoVersionId").asText();
        String photo = photosAt(eventId) + "/" + photoId;
        String preferences = photo + "/preferences/me";
        String consent = photo + "/consents/me";
        String subjects = photo + "/versions/" + versionId + "/subjects";

        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.ROSTER_UNCONFIRMED);
        mvc.perform(put(preferences).header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("photoVersionId", versionId).put("value", 1).toString()))
                .andExpect(status().isNoContent());
        mvc.perform(get(preferences).param("photoVersionId", versionId)
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(1));
        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.ROSTER_UNCONFIRMED);
        setSubjects(subjects, ORGANIZER, 0, "organizer", "not_a_member")
                .andExpect(status().isBadRequest());
        setSubjects(subjects, PARTICIPANT, 0, "participant")
                .andExpect(status().isForbidden());
        setSubjects(subjects, ORGANIZER, 0, "participant")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectRevision").value(1));
        setSubjects(subjects, ORGANIZER, 1, "participant")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectRevision").value(1));
        mvc.perform(get(subjects).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userIds[0]").value("participant"));
        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        setConsent(consent, ORGANIZER, versionId, 1, "PUBLIC_POST", "ALLOW")
                .andExpect(status().isForbidden());
        setConsent(consent, PARTICIPANT, versionId, 1, "PUBLIC_POST", "ALLOW")
                .andExpect(status().isNoContent());
        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.ELIGIBLE);
        assertEligibility(eventId, photoId, versionId, Purpose.INTERNAL_SHARE, Reason.CONSENT_PENDING);
        setConsent(consent, PARTICIPANT, versionId, 1, "PUBLIC_POST", "DENY")
                .andExpect(status().isNoContent());
        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.CONSENT_DENIED);

        setSubjects(subjects, ORGANIZER, 1, "participant", "organizer")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectRevision").value(2));
        setConsent(consent, PARTICIPANT, versionId, 1, "PUBLIC_POST", "ALLOW")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get(consent).param("photoVersionId", versionId).param("purpose", "PUBLIC_POST")
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectRevision").value(2))
                .andExpect(jsonPath("$.decision").value("PENDING"))
                .andExpect(jsonPath("$.canDecide").value(true));
        assertEligibility(eventId, photoId, versionId, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        assertThat(jdbc.queryForObject("""
                SELECT decision FROM photo_consents WHERE photo_version_id = ? AND subject_revision = 1
                """, String.class, versionId)).isEqualTo("DENY");
    }

    @Test
    void replacesOnlyTheCurrentMembersRatingAndPreservesZero() throws Exception {
        String eventId = createEvent("Rating", true);
        JsonNode uploaded = upload(eventId, "png");
        String photoId = uploaded.required("photoId").asText();
        String versionId = uploaded.required("photoVersionId").asText();
        String path = photosAt(eventId) + "/" + photoId + "/preferences/me";

        rate(path, versionId, ORGANIZER, -1);
        mvc.perform(get(path).param("photoVersionId", versionId).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(nullValue()));
        rate(path, versionId, PARTICIPANT, 0);
        mvc.perform(get(path).param("photoVersionId", versionId).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(0));
        rate(path, versionId, PARTICIPANT, 1);
        mvc.perform(get(path).param("photoVersionId", versionId).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(1));
        mvc.perform(get(path).param("photoVersionId", versionId).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(-1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM photo_preferences WHERE photo_version_id = ?",
                Long.class, versionId)).isEqualTo(2L);
    }

    @Test
    void reportsOnlyOrganizerEligibilityForTheSelectedPurposeAndCurrentRoster() throws Exception {
        String eventId = createEvent("Share progress", true);
        String progress = "/api/events/" + eventId + "/consent-progress";
        Map<String, Boolean> expected = new HashMap<>();
        for (String decision : new String[]{"ALLOW", "PENDING", "DENY"}) {
            JsonNode photo = upload(eventId, "png");
            String photoId = photo.required("photoId").asText();
            String versionId = photo.required("photoVersionId").asText();
            String path = photosAt(eventId) + "/" + photoId;
            setSubjects(path + "/versions/" + versionId + "/subjects", ORGANIZER, 0, "participant")
                    .andExpect(status().isOk());
            if (!"PENDING".equals(decision)) {
                setConsent(path + "/consents/me", PARTICIPANT, versionId, 1, "INTERNAL_SHARE", decision)
                        .andExpect(status().isNoContent());
            }
            mvc.perform(get(path + "/consents/me").param("photoVersionId", versionId).param("purpose", "INTERNAL_SHARE")
                            .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.decision").value(decision));
            expected.put(versionId, "ALLOW".equals(decision));
        }

        mvc.perform(get(progress).param("purpose", "INTERNAL_SHARE").header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        assertThat(shareProgress(progress, "INTERNAL_SHARE")).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(shareProgress(progress, "PUBLIC_POST").values()).hasSize(3).containsOnly(false);

        String allowedVersion = expected.entrySet().stream().filter(Map.Entry::getValue).findFirst().orElseThrow().getKey();
        String allowedPhoto = jdbc.queryForObject("SELECT photo_id FROM photo_versions WHERE id = ?", String.class, allowedVersion);
        String subjects = photosAt(eventId) + "/" + allowedPhoto + "/versions/" + allowedVersion + "/subjects";
        setSubjects(subjects, ORGANIZER, 1, "participant", "organizer").andExpect(status().isOk());
        assertThat(shareProgress(progress, "INTERNAL_SHARE").values()).hasSize(3).containsOnly(false);
    }

    private Map<String, Boolean> shareProgress(String path, String purpose) throws Exception {
        String body = mvc.perform(get(path).param("purpose", purpose).header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var result = new HashMap<String, Boolean>();
        for (var row : json.readTree(body)) {
            assertThat(row.size()).isEqualTo(3);
            assertThat(row.required("photoId").asText()).isNotBlank();
            result.put(row.required("photoVersionId").asText(), row.required("eligible").asBoolean());
        }
        return result;
    }

    private void rate(String path, String versionId, String token, int value) throws Exception {
        var body = json.createObjectNode().put("photoVersionId", versionId).put("value", value);
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isNoContent());
    }

    private void assertEligibility(String eventId, String photoId, String versionId, Purpose purpose, Reason result) {
        var eligibility = consents.evaluateForRecommendation(eventId, photoId, versionId, "organizer", purpose);
        assertThat(eligibility.reason()).isEqualTo(result);
        assertThat(eligibility.eligible()).isEqualTo(result == Reason.ELIGIBLE);
    }

    private ResultActions setSubjects(String target, String token, long revision, String... users) throws Exception {
        var body = json.createObjectNode().put("subjectRevision", revision);
        Arrays.stream(users).forEach(body.putArray("userIds")::add);
        return mvc.perform(put(target).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private ResultActions setConsent(String target, String token,
            String versionId, long revision, String purpose, String decision) throws Exception {
        var body = json.createObjectNode().put("photoVersionId", versionId).put("subjectRevision", revision)
                .put("purpose", purpose).put("decision", decision);
        return mvc.perform(put(target).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private String createEvent(String name, boolean includeParticipant) throws Exception {
        String response = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", name).put("desiredPhotoCount", 1).toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).required("id").asText();
        if (includeParticipant) {
            addParticipant(id);
        }
        return id;
    }

    private void addParticipant(String eventId) throws Exception {
        mvc.perform(post("/api/events/{id}/members", eventId).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                .andExpect(status().isNoContent());
    }

    private JsonNode upload(String eventId, String format) throws Exception {
        String body = mvc.perform(multipart(photosAt(eventId)).file(image(format))
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subjectRevision").value(0))
                .andExpect(jsonPath("$.rosterConfirmed").value(false))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static String photosAt(String eventId) {
        return "/api/events/" + eventId + "/photos";
    }

    private static MockMultipartFile image(String format) throws Exception {
        return new MockMultipartFile("file", "synthetic." + format, "image/" + format,
                SyntheticImages.image(format, 640, 320));
    }
}
