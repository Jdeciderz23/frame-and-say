package com.groupphoto.consensus.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupphoto.consensus.authorization.Publications.Purpose;
import com.groupphoto.consensus.authorization.Publications.Reason;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:authorization_boundaries;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.bootstrap.organizer-token=authorization_organizer_test_only_token",
        "app.bootstrap.participant-token=authorization_participant_test_only_token"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthorizationBoundaryIntegrationTest {
    private static final String ORGANIZER = "Bearer authorization_organizer_test_only_token";
    private static final String PARTICIPANT = "Bearer authorization_participant_test_only_token";

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
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

    private Photo shared;
    private Photo other;

    @BeforeEach
    void twoIdentitiesHaveDifferentRolesAcrossActivities() throws Exception {
        String event = createEvent(ORGANIZER);
        mvc.perform(post("/api/events/{event}/members", event).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"participant\"}"))
                .andExpect(status().isNoContent());
        shared = upload(event, ORGANIZER);
        other = upload(createEvent(PARTICIPANT), PARTICIPANT);
        roster(shared, ORGANIZER, 0, "participant").andExpect(status().isOk());
    }

    @Test
    void memberQueriesAndDecisionsAreScopedToTheAuthenticatedActivity() throws Exception {
        mvc.perform(get("/api/events/{event}/members", shared.eventId())
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/events/{event}/members", other.eventId())
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isForbidden());
        mvc.perform(get(other.consent()).param("photoVersionId", other.versionId()).param("purpose", "PUBLIC_POST")
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER)).andExpect(status().isForbidden());
        decide(other, ORGANIZER, 0, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isForbidden());
        assertThat(consentCount()).isZero();
    }

    @Test
    void membershipInBothActivitiesDoesNotPermitMixingPhotoAndVersionIds() throws Exception {
        var mixed = new Photo(other.eventId(), shared.photoId(), shared.versionId());
        decide(mixed, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNotFound());
        read(mixed, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isNotFound());
        var wrongVersion = new Photo(shared.eventId(), shared.photoId(), other.versionId());
        decide(wrongVersion, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNotFound());
        Photo sibling = upload(shared.eventId(), ORGANIZER);
        var anotherPhotosVersion = new Photo(shared.eventId(), shared.photoId(), sibling.versionId());
        read(anotherPhotosVersion, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isNotFound());
        decide(anotherPhotosVersion, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNotFound());
        assertThat(consentCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "actorId"})
    void aClientCannotSubmitSomeoneElsesDecision(String field) throws Exception {
        var body = decisionBody(shared, 1, Purpose.PUBLIC_POST, "ALLOW").put(field, "participant");
        mvc.perform(put(shared.consent()).header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
        decide(shared, ORGANIZER, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isForbidden());
        assertThat(consentCount()).isZero();
    }

    @Test
    void participantsCannotReadOrChangeTheFormalRosterOrReadAnotherDecision() throws Exception {
        mvc.perform(get(shared.roster()).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden());
        roster(shared, PARTICIPANT, 1, "organizer", "participant").andExpect(status().isForbidden());
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "DENY").andExpect(status().isNoContent());
        JsonNode mine = body(read(shared, ORGANIZER, Purpose.PUBLIC_POST).andExpect(status().isOk()));
        assertThat(mine.path("canDecide").asBoolean()).isFalse();
        assertThat(mine.path("decision").asText()).isEqualTo("PENDING");
        assertThat(mine.size()).isEqualTo(5);
        assertThat(mine.has("userIds")).isFalse();
        assertThat(mine.has("decisions")).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Purpose.class)
    void purposesAreIndependentAndRevocationChangesEligibility(Purpose allowed) throws Exception {
        Purpose denied = allowed == Purpose.PUBLIC_POST ? Purpose.INTERNAL_SHARE : Purpose.PUBLIC_POST;
        eligible(shared, allowed, Reason.CONSENT_PENDING);
        decide(shared, PARTICIPANT, 1, allowed, "ALLOW").andExpect(status().isNoContent());
        eligible(shared, allowed, Reason.ELIGIBLE);
        eligible(shared, denied, Reason.CONSENT_PENDING);
        decide(shared, PARTICIPANT, 1, denied, "DENY").andExpect(status().isNoContent());
        eligible(shared, allowed, Reason.ELIGIBLE);
        eligible(shared, denied, Reason.CONSENT_DENIED);
        decide(shared, PARTICIPANT, 1, allowed, "DENY").andExpect(status().isNoContent());
        eligible(shared, allowed, Reason.CONSENT_DENIED);
        assertThat(body(read(shared, PARTICIPANT, denied)).path("decision").asText()).isEqualTo("DENY");
    }

    @Test
    void everySubjectMustAllowTheSamePurpose() throws Exception {
        roster(shared, ORGANIZER, 1, "organizer", "participant").andExpect(status().isOk());
        decide(shared, PARTICIPANT, 2, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        eligible(shared, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        decide(shared, ORGANIZER, 2, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        eligible(shared, Purpose.PUBLIC_POST, Reason.ELIGIBLE);
        decide(shared, ORGANIZER, 2, Purpose.PUBLIC_POST, "DENY").andExpect(status().isNoContent());
        eligible(shared, Purpose.PUBLIC_POST, Reason.CONSENT_DENIED);
    }

    @Test
    void rosterChangeRequiresRefreshAndNewExplicitDecisions() throws Exception {
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        roster(shared, ORGANIZER, 1, "organizer", "participant").andExpect(status().isOk());
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isConflict());
        JsonNode refreshed = body(read(shared, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isOk()));
        assertThat(refreshed.path("subjectRevision").asLong()).isEqualTo(2);
        assertThat(refreshed.path("decision").asText()).isEqualTo("PENDING");
        assertThat(refreshed.path("canDecide").asBoolean()).isTrue();
        assertThat(consentCount()).isEqualTo(1);
        eligible(shared, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        decide(shared, PARTICIPANT, refreshed.path("subjectRevision").asLong(), Purpose.PUBLIC_POST, "ALLOW")
                .andExpect(status().isNoContent());
        eligible(shared, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        decide(shared, ORGANIZER, 2, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        eligible(shared, Purpose.PUBLIC_POST, Reason.ELIGIBLE);
    }

    @Test
    void aRemovedSubjectCannotReuseOldOrCurrentRevisionPermission() throws Exception {
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        roster(shared, ORGANIZER, 1, "organizer").andExpect(status().isOk());
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isConflict());
        decide(shared, PARTICIPANT, 2, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isForbidden());
        read(shared, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(false)).andExpect(jsonPath("$.decision").value("PENDING"));
        assertThat(consentCount()).isEqualTo(1);
        eligible(shared, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
    }

    @Test
    void submittingTheSameConfirmedRosterKeepsItsDecisions() throws Exception {
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        roster(shared, ORGANIZER, 1, "participant").andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectRevision").value(1));
        read(shared, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"));
        eligible(shared, Purpose.PUBLIC_POST, Reason.ELIGIBLE);
    }

    @Test
    void anUnconfirmedEmptyRosterIsNotAnExplicitlyConfirmedEmptyRoster() throws Exception {
        var before = consents.evaluateForRecommendation(other.eventId(), other.photoId(), other.versionId(),
                "participant", Purpose.PUBLIC_POST);
        assertThat(before.eligible()).isFalse();
        assertThat(before.reason()).isEqualTo(Reason.ROSTER_UNCONFIRMED);
        roster(other, PARTICIPANT, 0).andExpect(status().isOk());
        var after = consents.evaluateForRecommendation(other.eventId(), other.photoId(), other.versionId(),
                "participant", Purpose.PUBLIC_POST);
        assertThat(after.eligible()).isTrue();
    }

    @Test
    void aNewVersionDoesNotInheritPermissionAndOldVersionRequestsConflict() throws Exception {
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isNoContent());
        Photo next = insertNewVersionFixture(shared);
        mvc.perform(get("/api/events/{event}/photos", shared.eventId())
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].photoVersionId").value(next.versionId()));
        read(next, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isOk())
                .andExpect(jsonPath("$.canDecide").value(false));
        roster(next, ORGANIZER, 0, "participant").andExpect(status().isOk());
        read(next, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("PENDING"));
        read(shared, PARTICIPANT, Purpose.PUBLIC_POST).andExpect(status().isConflict());
        decide(shared, PARTICIPANT, 1, Purpose.PUBLIC_POST, "ALLOW").andExpect(status().isConflict());
        assertThatThrownBy(() -> consents.evaluateForRecommendation(shared.eventId(), shared.photoId(),
                shared.versionId(), "organizer", Purpose.PUBLIC_POST))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        decide(next, PARTICIPANT, 1, Purpose.INTERNAL_SHARE, "ALLOW").andExpect(status().isNoContent());
        eligible(next, Purpose.INTERNAL_SHARE, Reason.ELIGIBLE);
        eligible(next, Purpose.PUBLIC_POST, Reason.CONSENT_PENDING);
        assertThat(jdbc.queryForObject("SELECT decision FROM photo_consents WHERE photo_version_id = ?",
                String.class, shared.versionId())).isEqualTo("ALLOW");
    }

    // Version upload is a separate task; create only the metadata needed to test authorization isolation.
    private Photo insertNewVersionFixture(Photo original) {
        jdbc.queryForObject("SELECT id FROM photos WHERE id = ? FOR UPDATE", String.class, original.photoId());
        String version = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO photo_versions (id, photo_id, event_id, version_number, media_type)
                VALUES (?, ?, ?, 2, 'image/png')
                """, version, original.photoId(), original.eventId());
        return new Photo(original.eventId(), original.photoId(), version);
    }

    private String createEvent(String token) throws Exception {
        return body(mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Authorization fixture\"}"))
                .andExpect(status().isCreated())).path("id").asText();
    }

    private Photo upload(String event, String token) throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        JsonNode photo = body(mvc.perform(multipart("/api/events/{event}/photos", event)
                .file(new MockMultipartFile("file", "synthetic.png", "image/png", output.toByteArray()))
                .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isCreated()));
        return new Photo(event, photo.path("photoId").asText(), photo.path("photoVersionId").asText());
    }

    private ResultActions roster(Photo photo, String token, long revision, String... people) throws Exception {
        var request = json.createObjectNode().put("subjectRevision", revision);
        Arrays.stream(people).forEach(request.putArray("userIds")::add);
        return mvc.perform(put(photo.roster()).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(request.toString()));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode decisionBody(Photo photo, long revision,
                                                                       Purpose purpose, String decision) {
        return json.createObjectNode().put("photoVersionId", photo.versionId()).put("subjectRevision", revision)
                .put("purpose", purpose.name()).put("decision", decision);
    }

    private ResultActions decide(Photo photo, String token, long revision, Purpose purpose, String decision)
            throws Exception {
        return mvc.perform(put(photo.consent()).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(decisionBody(photo, revision, purpose, decision).toString()));
    }

    private ResultActions read(Photo photo, String token, Purpose purpose) throws Exception {
        return mvc.perform(get(photo.consent()).param("photoVersionId", photo.versionId()).param("purpose", purpose.name())
                .header(HttpHeaders.AUTHORIZATION, token));
    }

    private void eligible(Photo photo, Purpose purpose, Reason reason) {
        var result = consents.evaluateForRecommendation(photo.eventId(), photo.photoId(), photo.versionId(), "organizer", purpose);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.eligible()).isEqualTo(reason == Reason.ELIGIBLE);
    }

    private int consentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM photo_consents", Integer.class);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private record Photo(String eventId, String photoId, String versionId) {
        String consent() {
            return "/api/events/" + eventId + "/photos/" + photoId + "/consents/me";
        }

        String roster() {
            return "/api/events/" + eventId + "/photos/" + photoId + "/versions/" + versionId + "/subjects";
        }
    }
}
