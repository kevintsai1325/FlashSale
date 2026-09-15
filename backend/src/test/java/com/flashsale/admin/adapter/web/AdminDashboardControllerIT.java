package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.admin.adapter.http.AnalyticsClient;
import com.flashsale.admin.adapter.http.AnalyticsClient.TrendPoint;
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
 * <p>P5 起，聚合數字（搶購請求、訂單、已付款金額、趨勢）來自 analytics-service，
 * 那個服務在這個測試環境裡不存在，所以 {@link AnalyticsClient} 是 mock 的。
 * **這個測試守的是「把聚合與本地的庫存併起來、並補齊缺席的訂單狀態」這件事，
 * 不是那一次跨服務呼叫本身。** 呼叫的降級行為由 {@code AnalyticsClientTest} 守。
 */
class AdminDashboardControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean AnalyticsClient analyticsClient;

    // mock 依呼叫端給的錨點算桶，與真的 analytics-service 做的事一樣——
    // 回傳固定的桶起點會在測試跨越分鐘邊界時偶發性地對不上。
    @BeforeEach
    void stubAnalytics() {
        when(analyticsClient.summary()).thenReturn(new AnalyticsClient.DashboardSummary(
            7, 3,
            Map.of("PENDING_PAYMENT", 1L, "PAID", 3L, "CANCELLED", 1L, "EXPIRED", 1L),
            new java.math.BigDecimal("60.00")));
        when(analyticsClient.trends(any())).thenAnswer(invocation -> {
            Instant asOf = invocation.getArgument(0);
            return new AnalyticsClient.DashboardTrends(
                buckets(asOf, ChronoUnit.MINUTES, 60, Map.of(0, 5L), Map.of(0, 4L)),
                buckets(asOf, ChronoUnit.HOURS, 24, Map.of(0, 5L, 2, 1L), Map.of(0, 4L, 2, 1L)));
        });
    }

    /** {@code countsByAgo} 的鍵是「幾個單位以前」。 */
    private static List<TrendPoint> buckets(Instant asOf, ChronoUnit unit, int count,
                                             Map<Integer, Long> requestsByAgo, Map<Integer, Long> ordersByAgo) {
        Instant floored = asOf.truncatedTo(unit);
        List<TrendPoint> result = new ArrayList<>(count);
        for (int ago = count - 1; ago >= 0; ago--) {
            result.add(new TrendPoint(floored.minus(ago, unit),
                requestsByAgo.getOrDefault(ago, 0L), ordersByAgo.getOrDefault(ago, 0L)));
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

        // 搶購請求與訂單的數字全部由 stubAnalytics() 供應：那些聚合已經不在這個資料庫裡。
        // 本地只剩庫存 —— 它不是事件的聚合，而是「當下的狀態」，由擁有者直接回答。

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

        // 兩條趨勢線都來自同一份讀取模型，所以它們的桶起點必然對齊 ——
        // 這正是把聚合搬到 analytics 之後拿到的東西。
        assertThat(lastHourPurchaseRequests).isEqualTo(5);
        assertThat(lastHourOrders).isEqualTo(4);
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

    private long sumField(com.fasterxml.jackson.databind.JsonNode bucketArray, String field) {
        long sum = 0;
        for (com.fasterxml.jackson.databind.JsonNode bucket : bucketArray) {
            sum += bucket.get(field).asLong();
        }
        return sum;
    }
}
