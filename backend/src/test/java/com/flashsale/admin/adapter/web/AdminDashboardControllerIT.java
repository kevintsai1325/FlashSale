package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.admin.adapter.http.PurchaseStatsClient;
import com.flashsale.admin.adapter.http.PurchaseStatsClient.BucketCount;
import com.flashsale.admin.adapter.http.PurchaseStatsClient.PurchaseStats;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the admin dashboard read APIs end to end: real HTTP requests through Spring
 * Security (real JWTs from register+login, with the seeded user's role flipped to ADMIN via
 * direct JDBC since there is no admin-registration endpoint), against data seeded directly via
 * JDBC across different statuses and timestamps.
 *
 * <p>This is also the first real test of the {@code /api/admin/**} security boundary that has
 * existed since Week 1 with nothing behind it, so a plain {@code USER}-role JWT must be denied
 * on both endpoints.
 *
 * <p>P4 步驟 2 起，搶購請求的數字來自 purchase-service，那個服務在這個測試環境裡不存在，
 * 所以 {@link PurchaseStatsClient} 是 mock 的。**這個測試守的是「把別人的統計與本地的訂單、
 * 庫存併起來」這件事，不是那一次跨服務呼叫本身。** 呼叫本身的行為（逾時、失敗降級）
 * 由 {@code PurchaseStatsClient} 自己的單元測試守。
 */
class AdminDashboardControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean PurchaseStatsClient purchaseStatsClient;

    // mock 依呼叫端給的錨點算桶，與真的 purchase-service 做的事一樣——
    // 回傳固定的桶起點會在測試跨越分鐘邊界時偶發性地對不上。
    @BeforeEach
    void stubPurchaseStats() {
        when(purchaseStatsClient.fetch(any())).thenAnswer(invocation -> {
            Instant asOf = invocation.getArgument(0);
            return new PurchaseStats(7, 3,
                buckets(asOf, ChronoUnit.MINUTES, 60, Map.of(0, 5L)),
                buckets(asOf, ChronoUnit.HOURS, 24, Map.of(0, 5L, 2, 1L)));
        });
    }

    /** {@code countsByAgo} 的鍵是「幾個單位以前」，對應測試資料裡那些 now / 2 hours ago 的列。 */
    private static List<BucketCount> buckets(Instant asOf, ChronoUnit unit, int count, Map<Integer, Long> countsByAgo) {
        Instant floored = asOf.truncatedTo(unit);
        List<BucketCount> result = new ArrayList<>(count);
        for (int ago = count - 1; ago >= 0; ago--) {
            result.add(new BucketCount(floored.minus(ago, unit), countsByAgo.getOrDefault(ago, 0L)));
        }
        return result;
    }

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

    @Test
    void adminSeesAggregatedSummaryAndTimeBucketedTrendsMatchingSeededData() throws Exception {
        String adminToken = registerAdminAndLogin("dash-admin@example.com");

        jdbcTemplate.update("insert into users (email, password_hash, role) values ('dash-shopper@example.com', 'x', 'USER')");

        jdbcTemplate.update("insert into products (id, name) values (501, 'Dashboard Product')");
        jdbcTemplate.update(
            "insert into flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (501, 501, 9.99, now() - interval '1 hour', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update(
            "insert into inventory (flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "values (501, 10, 4, 1, 5, 0)");

        jdbcTemplate.update(
            "insert into flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (502, 501, 19.99, now() - interval '1 hour', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update(
            "insert into inventory (flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "values (502, 20, 20, 0, 0, 0)");

        // 搶購請求的數字由 stubPurchaseStats() 供應：全時段 7 筆、3 筆成功，
        // 一小時窗內 5 筆、24 小時窗內 6 筆（多的那一筆在兩小時前）。

        // orders: 6 total, 3 PAID totalling 60.00. Same time-window split as above.
        insertOrder("ORD-DASH-1", "dash-shopper@example.com", "10.00", "PENDING_PAYMENT", "now()");
        insertOrder("ORD-DASH-2", "dash-shopper@example.com", "10.00", "PAID", "now()");
        insertOrder("ORD-DASH-3", "dash-shopper@example.com", "20.00", "PAID", "now() - interval '2 hours'");
        insertOrder("ORD-DASH-4", "dash-shopper@example.com", "30.00", "PAID", "now() - interval '2 days'");
        insertOrder("ORD-DASH-5", "dash-shopper@example.com", "10.00", "CANCELLED", "now()");
        insertOrder("ORD-DASH-6", "dash-shopper@example.com", "10.00", "EXPIRED", "now()");

        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalPurchaseRequests").value(7))
            .andExpect(jsonPath("$.succeededPurchaseRequests").value(3))
            .andExpect(jsonPath("$.ordersByStatus.PENDING_PAYMENT").value(1))
            .andExpect(jsonPath("$.ordersByStatus.PAID").value(3))
            .andExpect(jsonPath("$.ordersByStatus.CANCELLED").value(1))
            .andExpect(jsonPath("$.ordersByStatus.EXPIRED").value(1))
            .andExpect(jsonPath("$.totalPaidAmount").value(60.00))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.501.totalQuantity").value(10))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.501.availableQuantity").value(4))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.501.reservedQuantity").value(1))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.501.soldQuantity").value(5))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.502.totalQuantity").value(20))
            .andExpect(jsonPath("$.inventoryByFlashSaleId.502.availableQuantity").value(20));

        MvcResult trendsResult = mockMvc.perform(get("/api/admin/dashboard/trends").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andReturn();

        var trends = objectMapper.readTree(trendsResult.getResponse().getContentAsString());

        long lastHourPurchaseRequests = sumField(trends.get("lastHour"), "purchaseRequestCount");
        long lastHourOrders = sumField(trends.get("lastHour"), "orderCount");
        long last24HoursPurchaseRequests = sumField(trends.get("last24Hours"), "purchaseRequestCount");
        long last24HoursOrders = sumField(trends.get("last24Hours"), "orderCount");

        // lastHour excludes ORD-DASH-3/4 (2h/2d old); 搶購請求那一半來自 stub。
        assertThat(lastHourPurchaseRequests).isEqualTo(5);
        assertThat(lastHourOrders).isEqualTo(4);
        // last24Hours excludes only ORD-DASH-4 (2 days old, outside the 24h window).
        assertThat(last24HoursPurchaseRequests).isEqualTo(6);
        assertThat(last24HoursOrders).isEqualTo(5);
    }

    @Test
    void nonAdminUserIsForbiddenFromBothDashboardEndpoints() throws Exception {
        String userToken = registerAndLogin("dash-plain-user@example.com");

        mockMvc.perform(get("/api/admin/dashboard/summary").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/dashboard/trends").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }

    private void insertOrder(String orderNo, String email, String amount, String status, String createdAtExpr) {
        jdbcTemplate.update(
            "insert into orders (order_no, user_id, total_amount, status, created_at) " +
            "values (?, (select id from users where email = ?), ?, ?, " + createdAtExpr + ")",
            orderNo, email, new java.math.BigDecimal(amount), status);
    }

    private long sumField(com.fasterxml.jackson.databind.JsonNode bucketArray, String field) {
        long sum = 0;
        for (com.fasterxml.jackson.databind.JsonNode bucket : bucketArray) {
            sum += bucket.get(field).asLong();
        }
        return sum;
    }
}
