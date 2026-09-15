package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.client.OrderServiceClient;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 後台訂單清單與詳情的 **BFF** 行為（P5）。
 *
 * 訂單資料來自 order-service（mock），稽核紀錄來自 platform 自己的資料庫（真的寫進去）。
 * 這個測試守的就是那個合併，以及「狀態篩選原封不動傳給擁有者」——
 * 訂單本身的查詢與分頁是 order-service 的責任，由它自己的測試負責。
 *
 * 這也是拆分後整合測試的一般形狀：只驗自己這一側做的事。
 */
class AdminOrderControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final long ORDER_ID = 4242L;
    private static final UUID PURCHASE_REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private String requestBody(String email) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", "secret123"));
    }

    private String registerAdminAndLogin(String email) throws Exception {
        String body = requestBody(email);
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email);
        // 角色在登入時就烤進 JWT，所以升級之後必須重新登入。
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
    void mergesOrderDetailFromOrderServiceWithPlatformOwnAuditLogs() throws Exception {
        String adminToken = registerAdminAndLogin("order-admin@example.com");
        jdbcTemplate.update("insert into users (email, password_hash, role) values ('order-shopper@example.com', 'x', 'USER')");
        jdbcTemplate.update("insert into users (email, password_hash, role) values ('order-other@example.com', 'x', 'USER')");
        Long shopperId = userId("order-shopper@example.com");
        Long otherShopperId = userId("order-other@example.com");

        Instant pendingAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        when(orderServiceClient.orderDetail(ORDER_ID)).thenReturn(Optional.of(new OrderServiceClient.OrderDetail(
            ORDER_ID, "ORD-ADMIN-1", shopperId, new BigDecimal("25.00"), "PAID",
            null, pendingAt, 601L, PURCHASE_REQUEST_ID,
            List.of(new OrderServiceClient.OrderItemView(601L, "Admin Order Product", 2, new BigDecimal("12.50"))),
            List.of(new OrderServiceClient.StatusHistoryView(null, "PENDING_PAYMENT", pendingAt),
                new OrderServiceClient.StatusHistoryView("PENDING_PAYMENT", "PAID", pendingAt.plusSeconds(60))))));

        // 時間窗內、同一個使用者 -> 會被帶進回應。
        insertAuditLog("/api/purchase", shopperId, "trace-order-creation", "now() - interval '10 minutes'");
        // 時間窗外 -> 排除。
        insertAuditLog("/api/purchase", shopperId, "trace-far-away", "now() - interval '30 minutes'");
        // 時間窗內但是別人的 -> 排除。
        insertAuditLog("/api/purchase", otherShopperId, "trace-other-user", "now() - interval '10 minutes'");

        mockMvc.perform(get("/api/admin/orders/" + ORDER_ID).header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderNo").value("ORD-ADMIN-1"))
            .andExpect(jsonPath("$.status").value("PAID"))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].productId").value(601))
            // 訂單存在本身就是搶購成功的證明，所以狀態是推導的，不必再跨一次服務去問。
            .andExpect(jsonPath("$.purchaseRequest.requestId").value(PURCHASE_REQUEST_ID.toString()))
            .andExpect(jsonPath("$.purchaseRequest.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.statusHistory.length()").value(2))
            // 這一項是 platform 自己的資料，也是這個端點不跟著訂單搬走的理由。
            .andExpect(jsonPath("$.relatedApiLogs.length()").value(1))
            .andExpect(jsonPath("$.relatedApiLogs[0].traceId").value("trace-order-creation"));
    }

    @Test
    void passesTheStatusFilterThroughToTheOwner() throws Exception {
        String adminToken = registerAdminAndLogin("order-filter-admin@example.com");
        when(orderServiceClient.orders(eq("CANCELLED"), eq(0), eq(20)))
            .thenReturn(new OrderServiceClient.PagedOrders(List.of(new OrderServiceClient.OrderSummary(
                7L, "ORD-CANCELLED", 1L, new BigDecimal("20.00"), "CANCELLED", Instant.now())), 1, 0, 20));

        mockMvc.perform(get("/api/admin/orders").param("status", "CANCELLED")
                .header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].orderNo").value("ORD-CANCELLED"));

        // 沒帶 status 時傳 null 過去，而不是某個預設值 —— 「全部狀態」的語意由擁有者定義。
        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk());
        org.mockito.Mockito.verify(orderServiceClient).orders(isNull(), eq(0), eq(20));
    }

    @Test
    void unknownOrderIdReturnsNotFound() throws Exception {
        String adminToken = registerAdminAndLogin("order-404-admin@example.com");
        // 共用底座預設就回 Optional.empty()：「查不到」是權威的答案，不是失敗。
        mockMvc.perform(get("/api/admin/orders/999999").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminUserIsForbidden() throws Exception {
        String body = requestBody("order-plain-user@example.com");
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
    }
}
