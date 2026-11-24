package com.groupphoto.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupphoto.consensus.support.SyntheticActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:event_membership_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        SyntheticActors.ORGANIZER_PROPERTY,
        SyntheticActors.PARTICIPANT_PROPERTY
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventMembershipIntegrationTest {
    private static final String ORGANIZER = SyntheticActors.ORGANIZER;
    private static final String PARTICIPANT = SyntheticActors.PARTICIPANT;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void requiresBearerAndRejectsTheClientSupplyingAnActor() throws Exception {
        mvc.perform(get("/api/events"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer definitely_invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, PARTICIPANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Group trip","actorId":"organizer"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void createsOwnerAtomicallyAndScopesEventAccessToMembers() throws Exception {
        String eventId = createEvent("Studio");
        assertThat(jdbc.queryForObject("SELECT role FROM event_members WHERE event_id = ? AND user_id = ?",
                String.class, eventId, "organizer")).isEqualTo("ORGANIZER");

        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + eventId + "')].name").value("Studio"));
        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + eventId + "')]").isEmpty());
        mvc.perform(get("/api/events/{id}/members", eventId).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));

        addMember(eventId, PARTICIPANT, "participant", 403);
        addMember(eventId, ORGANIZER, "unprovisioned", 404);
        addMember(eventId, ORGANIZER, "participant", 204);
        addMember(eventId, ORGANIZER, "participant", 204);
        addMember(eventId, ORGANIZER, "organizer", 204);
        addMember(eventId, PARTICIPANT, "organizer", 403);

        mvc.perform(get("/api/events/{id}/members", eventId).header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].role").value("ORGANIZER"))
                .andExpect(jsonPath("$[1].userId").value("participant"))
                .andExpect(jsonPath("$[1].role").value("PARTICIPANT"));
        mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, PARTICIPANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + eventId + "')].name").value("Studio"));
    }

    private String createEvent(String name) throws Exception {
        String response = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", name).put("desiredPhotoCount", 3).toString()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.desiredPhotoCount").value(3))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).required("id").asText();
    }

    private void addMember(String eventId, String token, String userId, int expectedStatus) throws Exception {
        mvc.perform(post("/api/events/{id}/members", eventId).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("userId", userId).toString()))
                .andExpect(status().is(expectedStatus));
    }
}
