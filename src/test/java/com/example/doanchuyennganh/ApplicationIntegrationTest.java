package com.example.doanchuyennganh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ApplicationIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayRunsBaselineOnRealMysql() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void healthAndProbesAreUpWithoutExposingDetails() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            mvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(content().json("{\"status\":\"UP\"}", true));
        }
    }

    @Test
    void readinessIncludesDatabase() {
        // Inspect actual health group membership, since public health hides components.
        assertThat(healthGroups.get("readiness").isMember("db")).isTrue();
        var databaseHealth = (org.springframework.boot.actuate.health.HealthIndicator)
                healthContributors.getContributor("db");
        assertThat(databaseHealth.health().getStatus().getCode()).isEqualTo("UP");
    }

    @Autowired org.springframework.boot.actuate.health.HealthEndpointGroups healthGroups;
    @Autowired org.springframework.boot.actuate.health.HealthContributorRegistry healthContributors;

    @Test
    void sensitiveActuatorEndpointsAreNotExposed() throws Exception {
        for (String endpoint : new String[]{"env", "configprops", "beans", "heapdump", "loggers"}) {
            mvc.perform(get("/actuator/" + endpoint)).andExpect(status().isNotFound());
        }
    }

    @Test
    void invalidBodyReturns400ProblemDetail() throws Exception {
        mvc.perform(post("/api/examples/errors/validation")
                        .contentType("application/json").content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request validation failed."))
                .andExpect(jsonPath("$.instance").value("/api/examples/errors/validation"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void notFoundReturns404ProblemDetail() throws Exception {
        mvc.perform(get("/api/examples/errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Requested resource was not found."))
                .andExpect(jsonPath("$.instance").value("/api/examples/errors/not-found"));
    }

    @Test
    void unknownRouteReturns404InsteadOf500() throws Exception {
        mvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void malformedJsonReturns400InsteadOf500() throws Exception {
        mvc.perform(post("/api/examples/errors/validation")
                        .contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void unexpectedErrorReturns500WithoutInternalDiagnostics() throws Exception {
        mvc.perform(get("/api/examples/errors/internal"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.instance").value("/api/examples/errors/internal"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("Demo internal diagnostic"))));
    }
}
