package com.groupphoto.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.groupphoto.consensus.support.SyntheticActors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void retryUsesTheFirstCreationEvenWhileItsTransactionIsPending() throws Exception {
        String key = UUID.randomUUID().toString();
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retryStarted = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var original = workers.submit(() -> transactions.execute(tx -> {
                try {
                    String id = keyedEvent(ORGANIZER, key, "pending creation", 201);
                    inserted.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Commit was not released");
                    return id;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                mvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER))
                        .andExpect(jsonPath("$[?(@.name == 'pending creation')]").isEmpty());
                var retry = workers.submit(() -> {
                    retryStarted.countDown();
                    return keyedEvent(ORGANIZER, key, "pending creation", 201);
                });
                assertThat(retryStarted.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                String eventId = original.get(10, TimeUnit.SECONDS);
                assertThat(retry.get(10, TimeUnit.SECONDS)).isEqualTo(eventId);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE name = ?", Integer.class,
                        "pending creation")).isEqualTo(1);
                keyedEvent(ORGANIZER, key, "changed input", 409);
                assertThat(keyedEvent(PARTICIPANT, key, "another member", 201)).isNotEqualTo(eventId);
            } finally {
                release.countDown();
            }
        }
    }

    private String keyedEvent(String token, String key, String name, int expectedStatus) throws Exception {
        String body = mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(json.createObjectNode().put("name", name).toString()))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("id").asText();
    }

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
        String eventId = createEvent("Studio", 3);
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

    @Test
    void enforcesEventNameBoundaries() throws Exception {
        assertCreationRejected("   ", 3);

        String longestName = "秋".repeat(80);
        String eventId = createEvent(longestName, 3);
        assertThat(jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, eventId))
                .isEqualTo(longestName);

        assertCreationRejected(longestName + "游", 3);
    }

    @Test
    void trimsOuterSpacesAndPreservesConsecutiveInnerSpaces() throws Exception {
        String eventId = createEvent(" 秋游  合照 ", 3);
        assertThat(jdbc.queryForObject("SELECT name FROM events WHERE id = ?", String.class, eventId))
                .isEqualTo("秋游  合照");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void storesDesiredPhotoCountAtEitherLimit(int count) throws Exception {
        String eventId = createEvent("秋游", count);
        assertThat(jdbc.queryForObject("SELECT desired_photo_count FROM events WHERE id = ?", Integer.class, eventId))
                .isEqualTo(count);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void rejectsDesiredPhotoCountOutsideTheLimits(int count) throws Exception {
        assertCreationRejected("秋游", count);
    }

    private void assertCreationRejected(String name, int desiredPhotoCount) throws Exception {
        Long countBefore = jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class);
        postEvent(name, desiredPhotoCount).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isEqualTo(countBefore);
    }

    private ResultActions postEvent(String name, int desiredPhotoCount) throws Exception {
        return mvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, ORGANIZER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.createObjectNode().put("name", name).put("desiredPhotoCount", desiredPhotoCount).toString()));
    }

    private String createEvent(String name, int desiredPhotoCount) throws Exception {
        String response = postEvent(name, desiredPhotoCount)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.desiredPhotoCount").value(desiredPhotoCount))
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
