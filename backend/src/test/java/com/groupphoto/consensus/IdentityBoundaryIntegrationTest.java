package com.groupphoto.consensus;

import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER;
import static com.groupphoto.consensus.support.SyntheticActors.ORGANIZER_ID;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT;
import static com.groupphoto.consensus.support.SyntheticActors.PARTICIPANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupphoto.consensus.support.SyntheticActors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity_boundary_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        SyntheticActors.ORGANIZER_PROPERTY,
        SyntheticActors.PARTICIPANT_PROPERTY
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdentityBoundaryIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private String organizerEvent;
    private String participantEvent;

    @BeforeEach
    void createSeparateActivitiesThroughTheAuthenticatedApi() throws Exception {
        organizerEvent = createEvent(ORGANIZER, "Organizer's activity");
        participantEvent = createEvent(PARTICIPANT, "Participant's own activity");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Bearer invalid_test_token", "Basic invalid_test_credentials"})
    void missingOrInvalidCredentialsCannotReadMembers(String authorization) throws Exception {
        var request = get("/api/events/{id}/members", organizerEvent);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "actorId", "role"})
    void forgedActorFieldsAreRejectedWithoutCreatingRecords(String field) throws Exception {
        var body = json.createObjectNode().put("name", "Forged activity").put(field, ORGANIZER_ID);
        mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_members", Integer.class)).isEqualTo(2);
    }

    @Test
    void clientHeadersAndQueryParametersCannotSelectTheCaller() throws Exception {
        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .header("X-User-Id", ORGANIZER_ID).header("X-Actor-Id", ORGANIZER_ID)
                        .param("userId", ORGANIZER_ID).param("actorId", ORGANIZER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(participantEvent));
    }

    @Test
    void nonMembersCannotReadOrChangeAnotherActivitiesMembership() throws Exception {
        mvc.perform(get("/api/events/{id}/members", organizerEvent)
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/events/{id}/members", organizerEvent)
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("userId", PARTICIPANT_ID).toString()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_members WHERE event_id = ? AND user_id = ?",
                Integer.class, organizerEvent, PARTICIPANT_ID)).isZero();
    }

    @Test
    void memberRequestUserIdNamesTheTargetWithoutChangingTheCaller() throws Exception {
        mvc.perform(post("/api/events/{id}/members", organizerEvent)
                        .header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("userId", PARTICIPANT_ID).toString()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/events/{id}/members", organizerEvent)
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(post("/api/events/{id}/members", organizerEvent)
                        .header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("userId", ORGANIZER_ID).toString()))
                .andExpect(status().isForbidden());
        assertThat(role(organizerEvent, PARTICIPANT_ID)).isEqualTo("PARTICIPANT");
        assertThat(role(organizerEvent, ORGANIZER_ID)).isEqualTo("ORGANIZER");
    }

    @Test
    void bothAccountsCreateActivitiesUsingTheirAuthenticatedIdentity() {
        assertThat(role(organizerEvent, ORGANIZER_ID)).isEqualTo("ORGANIZER");
        assertThat(role(participantEvent, PARTICIPANT_ID)).isEqualTo("ORGANIZER");
    }

    private String createEvent(String authorization, String name) throws Exception {
        var response = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", name).toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).required("id").asText();
    }

    private String role(String eventId, String userId) {
        return jdbc.queryForObject("SELECT role FROM event_members WHERE event_id = ? AND user_id = ?",
                String.class, eventId, userId);
    }
}
