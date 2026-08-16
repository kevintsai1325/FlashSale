package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises {@code GET /api/admin/orders} (list) and {@code GET /api/admin/orders/{id}}
 * (detail) end to end, same ADMIN/USER JWT pattern as {@link AdminDashboardControllerIT}.
 *
 * <p>Seeds an order + item + linked purchase request + two {@code order_status_history} rows
 * directly via JDBC (this module has no order-creation flow of its own to drive through), plus
 * three {@code api_audit_logs} rows to prove the detail endpoint's time-window audit-log
 * correlation (see {@code AdminOrderQueryService}): one within the correlation window of the
 * order's {@code PENDING_PAYMENT} timestamp and same user (expected in the response), one far
 * outside the window (excluded), and one inside the window but for a different user (excluded).
 */
class AdminOrderControllerIT extends AbstractIntegrationTest {

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

    private void insertAuditLog(String pathTemplate, Long userId, String traceId, String occurredAtExpr) {
        jdbcTemplate.update(
            "insert into api_audit_logs " +
            "(occurred_at, method, path_template, status, user_id, request_id, trace_id, duration_ms, client_ip, user_agent, error_code) " +
            "values (" + occurredAtExpr + ", 'POST', ?, 201, ?, ?, ?, 5, '127.0.0.1', 'test-agent', null)",
            pathTemplate, userId, traceId, traceId);
    }

    @Test
    void adminSeesOrderListAndDetailWithHistoryAndCorrelatedAuditLogs() throws Exception {
        String adminToken = registerAdminAndLogin("order-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('order-shopper@example.com', 'x', 'USER')");
        jdbcTemplate.update("insert into users (email, password_hash, role) values ('order-other-shopper@example.com', 'x', 'USER')");
        Long shopperId = userId("order-shopper@example.com");
        Long otherShopperId = userId("order-other-shopper@example.com");

        jdbcTemplate.update("insert into products (id, name) values (601, 'Admin Order Product')");
        jdbcTemplate.update(
            "insert into flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (601, 601, 12.50, now() - interval '1 hour', now() + interval '1 hour', 2, 'ACTIVE')");

        jdbcTemplate.update(
            "insert into orders (order_no, user_id, total_amount, status, created_at) " +
            "values ('ORD-ADMIN-1', ?, 25.00, 'PAID', now() - interval '10 minutes')", shopperId);
        Long orderId = jdbcTemplate.queryForObject("select id from orders where order_no = 'ORD-ADMIN-1'", Long.class);

        jdbcTemplate.update(
            "insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (?, 601, 'Admin Order Product', 2, 12.50)", orderId);

        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status, created_at) " +
            "values (gen_random_uuid(), 'admin-order-key', ?, 601, ?, 'SUCCEEDED', now() - interval '10 minutes')",
            shopperId, orderId);

        jdbcTemplate.update(
            "insert into order_status_history (order_id, from_status, to_status, changed_at) " +
            "values (?, null, 'PENDING_PAYMENT', now() - interval '10 minutes')", orderId);
        jdbcTemplate.update(
            "insert into order_status_history (order_id, from_status, to_status, changed_at) " +
            "values (?, 'PENDING_PAYMENT', 'PAID', now() - interval '9 minutes')", orderId);

        // Within the correlation window (same time as PENDING_PAYMENT), same user -> expected.
        insertAuditLog("/api/purchase", shopperId, "trace-order-creation", "now() - interval '10 minutes'");
        // Far outside the window -> excluded.
        insertAuditLog("/api/purchase", shopperId, "trace-far-away", "now() - interval '30 minutes'");
        // Inside the window but a different user -> excluded.
        insertAuditLog("/api/purchase", otherShopperId, "trace-other-user", "now() - interval '10 minutes'");

        // Scoped to status=PAID so this assertion stays valid regardless of what other order rows
        // other test methods in this class may have inserted into the shared Testcontainers DB
        // (see filtersOrderListByStatus, which deliberately avoids the PAID status for this reason).
        mockMvc.perform(get("/api/admin/orders").param("status", "PAID").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderNo").value("ORD-ADMIN-1"))
            .andExpect(jsonPath("$.content[0].status").value("PAID"))
            .andExpect(jsonPath("$.content[0].totalAmount").value(25.00));

        mockMvc.perform(get("/api/admin/orders/" + orderId).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderNo").value("ORD-ADMIN-1"))
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].productId").value(601))
            .andExpect(jsonPath("$.items[0].quantity").value(2))
            .andExpect(jsonPath("$.purchaseRequest.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.purchaseRequest.orderId").value(orderId))
            .andExpect(jsonPath("$.statusHistory.length()").value(2))
            .andExpect(jsonPath("$.statusHistory[0].fromStatus").doesNotExist())
            .andExpect(jsonPath("$.statusHistory[0].toStatus").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.statusHistory[1].fromStatus").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.statusHistory[1].toStatus").value("PAID"))
            .andExpect(jsonPath("$.relatedApiLogs.length()").value(1))
            .andExpect(jsonPath("$.relatedApiLogs[0].traceId").value("trace-order-creation"));
    }

    @Test
    void filtersOrderListByStatus() throws Exception {
        String adminToken = registerAdminAndLogin("order-status-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('order-status-shopper@example.com', 'x', 'USER')");
        Long shopperId = userId("order-status-shopper@example.com");

        jdbcTemplate.update("insert into products (id, name) values (701, 'Status Filter Product')");
        jdbcTemplate.update(
            "insert into flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (701, 701, 9.99, now() - interval '1 hour', now() + interval '1 hour', 2, 'ACTIVE')");

        // Deliberately CANCELLED + EXPIRED, not PAID: adminSeesOrderListAndDetailWithHistoryAndCorrelatedAuditLogs
        // (above) inserts a PAID order into this same shared Testcontainers DB, and test method
        // execution order within a class isn't guaranteed, so any assertion here scoped to PAID
        // (or an unscoped/unfiltered row-count assertion) could pass or fail depending on which
        // test method happens to run first. CANCELLED/EXPIRED are otherwise unused in this class.
        jdbcTemplate.update(
            "insert into orders (order_no, user_id, total_amount, status, created_at) " +
            "values ('ORD-STATUS-CANCELLED', ?, 20.00, 'CANCELLED', now() - interval '5 minutes')", shopperId);
        jdbcTemplate.update(
            "insert into orders (order_no, user_id, total_amount, status, created_at) " +
            "values ('ORD-STATUS-EXPIRED', ?, 30.00, 'EXPIRED', now() - interval '15 minutes')", shopperId);

        // No status filter -> includes both of this test's orders (order-independent: checks
        // presence via response body content, not an exact totalElements count, since other test
        // methods' orders may also be present in the shared table).
        MvcResult unfiltered = mockMvc.perform(get("/api/admin/orders").param("size", "100").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();
        String unfilteredBody = unfiltered.getResponse().getContentAsString();
        assertThat(unfilteredBody).contains("ORD-STATUS-CANCELLED").contains("ORD-STATUS-EXPIRED");

        // status=CANCELLED -> only the CANCELLED order (safe: no other test in this class uses CANCELLED).
        mockMvc.perform(get("/api/admin/orders").param("status", "CANCELLED").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderNo").value("ORD-STATUS-CANCELLED"));

        // status=EXPIRED -> only the EXPIRED order (safe: no other test in this class uses EXPIRED).
        mockMvc.perform(get("/api/admin/orders").param("status", "EXPIRED").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderNo").value("ORD-STATUS-EXPIRED"));
    }

    @Test
    void nonAdminUserIsForbiddenFromBothOrderEndpoints() throws Exception {
        String userToken = registerAndLogin("order-plain-user@example.com");

        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/orders/1").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrderIdReturnsNotFound() throws Exception {
        String adminToken = registerAdminAndLogin("order-admin-404@example.com");

        mockMvc.perform(get("/api/admin/orders/999999").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }
}
