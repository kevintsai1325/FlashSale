package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
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

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the admin notification center end to end: list/filter, detail, batch read-status,
 * retry (including the manual-retry-ignores-attempt-cap behavior and the status guard added in
 * Task 7 on top of Task 4's {@code NotificationRetryService.retry}), unread-count.
 *
 * <p>Same ADMIN/USER JWT pattern as {@link AdminOrderControllerIT}. {@code userA}/{@code userB}
 * are seeded directly via JDBC (never go through {@code /api/auth/register}), so they never
 * trigger the registration-success welcome email that {@code UserRegisteredNotificationListener}
 * fires for the admin account itself - filtered assertions scoped to {@code userA}/{@code userB}
 * are therefore immune to that background row. The one assertion that reads the *global* total
 * ({@code unread-count}) instead compares against a JDBC-computed count taken at the same point
 * in the test, rather than a hardcoded literal, so it stays correct regardless of whether/when
 * that async welcome-email send has landed.
 *
 * <p>{@code JavaMailSender} is {@code @MockBean}'d (same technique as
 * {@link com.flashsale.notification.application.NotificationRetryServiceIT}) so retry attempts
 * resolve deterministically to {@code SENT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminNotificationControllerIT {

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
        // See NotificationRetryServiceIT: @MockBean-ing JavaMailSender breaks Actuate's mail
        // health indicator, which specifically scans for a real JavaMailSenderImpl bean.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @MockBean
    JavaMailSender mailSender;

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

    private Long insertDelivery(Long userId, String channel, String template, String recipient, String status,
                                 int attemptCount, boolean read, String createdAtExpr) {
        return jdbcTemplate.queryForObject(
            "insert into notification_deliveries " +
            "(user_id, channel, template, recipient, status, attempt_count, is_read, created_at) " +
            "values (?, ?, ?, ?, ?, ?, ?, " + createdAtExpr + ") returning id",
            Long.class, userId, channel, template, recipient, status, attemptCount, read);
    }

    private String deliveryStatus(Long deliveryId) {
        return jdbcTemplate.queryForObject(
            "select status from notification_deliveries where id = ?", String.class, deliveryId);
    }

    private boolean isRead(Long deliveryId) {
        return jdbcTemplate.queryForObject(
            "select is_read from notification_deliveries where id = ?", Boolean.class, deliveryId);
    }

    private int attemptCount(Long deliveryId) {
        return jdbcTemplate.queryForObject(
            "select attempt_count from notification_deliveries where id = ?", Integer.class, deliveryId);
    }

    @Test
    void adminNotificationCenterSupportsListFilterDetailReadStatusRetryAndUnreadCount() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        String adminToken = registerAdminAndLogin("notif-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('notif-user-a@example.com', 'x', 'USER')");
        jdbcTemplate.update("insert into users (email, password_hash, role) values ('notif-user-b@example.com', 'x', 'USER')");
        Long userA = userId("notif-user-a@example.com");
        Long userB = userId("notif-user-b@example.com");

        // userA rows, newest first by created_at.
        Long inAppUnread = insertDelivery(userA, "IN_APP", "in-app-template", "a-inapp", "SENT", 0, false,
            "now() - interval '1 minutes'");
        Long pendingUnread = insertDelivery(userA, "SMS", "sms-template", "a-sms", "PENDING", 0, false,
            "now() - interval '2 minutes'");
        Long sentRead = insertDelivery(userA, "EMAIL", "registration-success", "a@example.com", "SENT", 0, true,
            "now() - interval '3 minutes'");

        // userB rows: FAILED, one below the retry cap and one already at it.
        Long failedOnce = insertDelivery(userB, "EMAIL", "registration-success", "b@example.com", "FAILED", 1, false,
            "now() - interval '4 minutes'");
        Long failedAtCap = insertDelivery(userB, "EMAIL", "registration-success", "b2@example.com", "FAILED", 3, false,
            "now() - interval '5 minutes'");

        // --- list: filter by userId ---
        mockMvc.perform(get("/api/admin/notifications").param("userId", userA.toString())
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].id").value(inAppUnread))
            .andExpect(jsonPath("$.content[0].channel").value("IN_APP"))
            .andExpect(jsonPath("$.content[1].id").value(pendingUnread))
            .andExpect(jsonPath("$.content[2].id").value(sentRead));

        // --- list: filter by userId + channel (combined) ---
        mockMvc.perform(get("/api/admin/notifications").param("userId", userA.toString()).param("channel", "SMS")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(pendingUnread));

        // --- list: filter by userId + read=false (combined) ---
        mockMvc.perform(get("/api/admin/notifications").param("userId", userA.toString()).param("read", "false")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(inAppUnread))
            .andExpect(jsonPath("$.content[1].id").value(pendingUnread));

        // --- list: filter by userId + status (combined) ---
        mockMvc.perform(get("/api/admin/notifications").param("userId", userB.toString()).param("status", "FAILED")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].id").value(failedOnce))
            .andExpect(jsonPath("$.content[1].id").value(failedAtCap));

        // --- list: status filter alone (global) - admin's own welcome email always resolves to
        // SENT (mailSender is stubbed to succeed), so it can never pollute a FAILED-only filter
        // regardless of whether that async send has landed by now.
        mockMvc.perform(get("/api/admin/notifications").param("status", "FAILED")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2));

        // --- detail ---
        mockMvc.perform(get("/api/admin/notifications/" + failedOnce)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(failedOnce))
            .andExpect(jsonPath("$.userId").value(userB))
            .andExpect(jsonPath("$.channel").value("EMAIL"))
            .andExpect(jsonPath("$.template").value("registration-success"))
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.attemptCount").value(1))
            .andExpect(jsonPath("$.read").value(false));

        mockMvc.perform(get("/api/admin/notifications/999999")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());

        // --- PATCH read-status: flip exactly the targeted ids ---
        String patchBody = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("ids", java.util.List.of(inAppUnread, pendingUnread));
                put("read", true);
            }});
        mockMvc.perform(patch("/api/admin/notifications/read-status")
                .contentType(APPLICATION_JSON).content(patchBody)
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());

        assertThat(isRead(inAppUnread)).isTrue();
        assertThat(isRead(pendingUnread)).isTrue();
        assertThat(isRead(sentRead)).isTrue(); // untouched, was already true
        assertThat(isRead(failedOnce)).isFalse(); // untouched
        assertThat(isRead(failedAtCap)).isFalse(); // untouched

        // --- unread-count: compare against a JDBC-computed total taken right now, not a
        // hardcoded literal, so the admin's own async welcome-email row (unread, arbitrary
        // timing) can't make this assertion flaky either way.
        long expectedUnread = jdbcTemplate.queryForObject(
            "select count(*) from notification_deliveries where is_read = false", Long.class);
        mockMvc.perform(get("/api/admin/notifications/unread-count")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(expectedUnread));

        // --- retry: FAILED row below the cap succeeds ---
        mockMvc.perform(post("/api/admin/notifications/" + failedOnce + "/retry")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());
        assertThat(deliveryStatus(failedOnce)).isEqualTo("SENT");

        // --- retry: FAILED row AT the scheduler's attempt cap still fires on manual retry - this
        // is the behavior distinguishing an admin-triggered retry from the scheduler's auto-retry
        // (design spec: manual retry ignores attemptCount). ---
        assertThat(attemptCount(failedAtCap)).isEqualTo(3);
        mockMvc.perform(post("/api/admin/notifications/" + failedAtCap + "/retry")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNoContent());
        assertThat(deliveryStatus(failedAtCap)).isEqualTo("SENT");

        // --- retry: a non-FAILED row (already SENT) is rejected, not silently re-sent - Task 7's
        // guard on top of NotificationRetryService.retry, which has no status precondition itself.
        mockMvc.perform(post("/api/admin/notifications/" + sentRead + "/retry")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isConflict());
        assertThat(deliveryStatus(sentRead)).isEqualTo("SENT"); // unchanged, not re-sent

        mockMvc.perform(post("/api/admin/notifications/999999/retry")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminUserIsForbiddenFromAllNotificationEndpoints() throws Exception {
        String userToken = registerAndLogin("notif-plain-user@example.com");

        mockMvc.perform(get("/api/admin/notifications").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/notifications/1").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/notifications/unread-count").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/notifications/1/retry").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/notifications/read-status").contentType(APPLICATION_JSON)
                .content("{\"ids\":[1],\"read\":true}").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
