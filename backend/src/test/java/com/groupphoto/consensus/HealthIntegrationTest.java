package com.groupphoto.consensus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import javax.sql.DataSource;

import com.groupphoto.consensus.support.SyntheticActors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.bootstrap.organizer-token=",
        "app.bootstrap.participant-token="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private DataSource dataSource;

    @Test
    void healthMatchesFrontendContract() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("group-photo-consensus"));
    }

    @Test
    void readinessIncludesAnActualDatabaseProbe() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void testProfileCanExecuteSqlAgainstH2() {
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.getDataSource()).isNotNull();
    }

    @Test
    void databaseFailureMakesReadinessUnavailableWithoutChangingTheHttpProbe() throws Exception {
        doThrow(new SQLException("Synthetic database outage")).when(dataSource).getConnection();

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unconfiguredIdentitiesKeepBusinessEndpointsClosed() throws Exception {
        mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, SyntheticActors.ORGANIZER))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/events").header(HttpHeaders.AUTHORIZATION, SyntheticActors.PARTICIPANT))
                .andExpect(status().isUnauthorized());
    }
}
