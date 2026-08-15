package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@code GET /api/admin/api-logs} end to end: real HTTP requests through Spring
 * Security (real JWTs from register+login, with the seeded user's role flipped to ADMIN via
 * direct JDBC, same pattern as {@link AdminDashboardControllerIT}), against
 * {@code api_audit_logs} rows seeded directly via JDBC with varying method/path/status/user/trace.
 *
 * <p>Filter assertions rely on distinctive {@code traceId}/{@code pathTemplate} values that no
 * real request made by this test (register/login/the api-logs call itself) would ever produce,
 * so the background {@link com.flashsale.common.web.ApiAuditFilter} writing its own rows for
 * those real requests cannot pollute the filtered result sets under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminApiLogControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
    }

    private String registerAndLogin(String email) throws Exception {
        String body = requestBody(email, "secret123");
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String registerAdminAndLogin(String email) throws Exception {
        String token = registerAndLogin(email);
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email);
        // Role is baked into the JWT at login time, so re-login after the promotion.
        String body = requestBody(email, "secret123");
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private Long userId(String email) {
        return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
    }

    private void insertAuditLog(String method, String pathTemplate, int status, Long userId, String traceId,
                                 String occurredAtExpr) {
        jdbcTemplate.update(
            "insert into api_audit_logs " +
            "(occurred_at, method, path_template, status, user_id, request_id, trace_id, duration_ms, client_ip, user_agent, error_code) " +
            "values (" + occurredAtExpr + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            method, pathTemplate, status, userId, traceId, traceId, 5, "127.0.0.1", "test-agent", null);
    }

    @Test
    void filtersApiAuditLogsByEachSupportedParamIndividuallyAndCombined() throws Exception {
        String adminToken = registerAdminAndLogin("apilog-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('apilog-shopper1@example.com', 'x', 'USER')");
        jdbcTemplate.update("insert into users (email, password_hash, role) values ('apilog-shopper2@example.com', 'x', 'USER')");
        Long shopper1Id = userId("apilog-shopper1@example.com");
        Long shopper2Id = userId("apilog-shopper2@example.com");

        // Row A: GET /api/orders/{orderId} 200, shopper1, most recent.
        insertAuditLog("GET", "/api/orders/{orderId}", 200, shopper1Id, "trace-aaa", "now()");
        // Row B: PUT /api/orders/{orderId}/cancel 201, shopper1, 1 minute older. PUT is used only
        // here — every *real* request this test drives (register/login/the api-logs calls under
        // test) is GET or POST, so filtering on PUT can't accidentally pick up background traffic
        // (unlike POST, which the register/login calls needed to mint JWTs also produce).
        insertAuditLog("PUT", "/api/orders/{orderId}/cancel", 201, shopper1Id, "trace-bbb", "now() - interval '1 minutes'");
        // Row C: GET /api/orders/{orderId} 404, shopper2, 2 minutes older.
        insertAuditLog("GET", "/api/orders/{orderId}", 404, shopper2Id, "trace-ccc", "now() - interval '2 minutes'");
        // Row D: GET /api/products/{id} 200, shopper2, 3 minutes older.
        insertAuditLog("GET", "/api/products/{id}", 200, shopper2Id, "trace-ddd", "now() - interval '3 minutes'");

        // Filter by method.
        mockMvc.perform(get("/api/admin/api-logs").param("method", "PUT").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-bbb"));

        // Filter by pathTemplate: rows A and C, desc by occurredAt (A newer than C).
        mockMvc.perform(get("/api/admin/api-logs").param("pathTemplate", "/api/orders/{orderId}").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-aaa"))
            .andExpect(jsonPath("$.content[1].traceId").value("trace-ccc"));

        // Filter by status.
        mockMvc.perform(get("/api/admin/api-logs").param("status", "404").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-ccc"));

        // Filter by userId: rows A and B, desc by occurredAt (A newer than B).
        mockMvc.perform(get("/api/admin/api-logs").param("userId", shopper1Id.toString()).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-aaa"))
            .andExpect(jsonPath("$.content[1].traceId").value("trace-bbb"));

        // Filter by traceId.
        mockMvc.perform(get("/api/admin/api-logs").param("traceId", "trace-ddd").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-ddd"));

        // Combined filter: GET + /api/orders/{orderId} + 200 -> only row A (excludes row C, which is 404).
        MvcResult combined = mockMvc.perform(get("/api/admin/api-logs")
                .param("method", "GET")
                .param("pathTemplate", "/api/orders/{orderId}")
                .param("status", "200")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-aaa"))
            .andReturn();
        assertThat(combined.getResponse().getContentAsString()).contains("trace-aaa");
    }

    @Test
    void filtersApiAuditLogsByOccurredAtTimeRange() throws Exception {
        String adminToken = registerAdminAndLogin("apilog-time-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('apilog-time-shopper@example.com', 'x', 'USER')");
        Long shopperId = userId("apilog-time-shopper@example.com");

        // Row OLD: 10 minutes ago. Row RECENT: 1 minute ago. Both scoped to a dedicated shopper so
        // filtering by userId isolates exactly these two rows from any background request logging.
        insertAuditLog("GET", "/api/time-range-test/old", 200, shopperId, "trace-time-old", "now() - interval '10 minutes'");
        insertAuditLog("GET", "/api/time-range-test/recent", 200, shopperId, "trace-time-recent", "now() - interval '1 minutes'");

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(5));

        // from only: excludes OLD (10 min ago, before cutoff), includes RECENT (1 min ago, after cutoff).
        mockMvc.perform(get("/api/admin/api-logs")
                .param("userId", shopperId.toString())
                .param("from", cutoff.toString())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-time-recent"));

        // to only: excludes RECENT (after cutoff), includes OLD (before cutoff).
        mockMvc.perform(get("/api/admin/api-logs")
                .param("userId", shopperId.toString())
                .param("to", cutoff.toString())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-time-old"));

        // from + to combined: narrow window bracketing only RECENT.
        mockMvc.perform(get("/api/admin/api-logs")
                .param("userId", shopperId.toString())
                .param("from", Instant.now().minus(Duration.ofMinutes(2)).toString())
                .param("to", Instant.now().toString())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].traceId").value("trace-time-recent"));
    }

    @Test
    void nonAdminUserIsForbiddenFromApiLogsEndpoint() throws Exception {
        String userToken = registerAndLogin("apilog-plain-user@example.com");

        mockMvc.perform(get("/api/admin/api-logs").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
