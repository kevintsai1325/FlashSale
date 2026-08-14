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

import static org.assertj.core.api.Assertions.assertThat;
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
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminDashboardControllerIT {

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

        // purchase_requests: 7 total, 3 SUCCEEDED. Timestamps split across "now", "2 hours ago"
        // (inside the 24h trend window but outside the 1h window), and "2 days ago" (outside
        // both trend windows, but still counted in the all-time summary).
        insertPurchaseRequest("pr-1", "dash-shopper@example.com", 501, "PENDING", "now()");
        insertPurchaseRequest("pr-2", "dash-shopper@example.com", 501, "SUCCEEDED", "now()");
        insertPurchaseRequest("pr-3", "dash-shopper@example.com", 501, "SUCCEEDED", "now() - interval '2 hours'");
        insertPurchaseRequest("pr-4", "dash-shopper@example.com", 501, "SUCCEEDED", "now() - interval '2 days'");
        insertPurchaseRequest("pr-5", "dash-shopper@example.com", 501, "SOLD_OUT", "now()");
        insertPurchaseRequest("pr-6", "dash-shopper@example.com", 501, "REJECTED", "now()");
        insertPurchaseRequest("pr-7", "dash-shopper@example.com", 501, "FAILED", "now()");

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

        // lastHour excludes pr-3/pr-4 (2h/2d old) and ORD-DASH-3/4 (2h/2d old).
        assertThat(lastHourPurchaseRequests).isEqualTo(5);
        assertThat(lastHourOrders).isEqualTo(4);
        // last24Hours excludes only pr-4/ORD-DASH-4 (2 days old, outside the 24h window).
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

    private void insertPurchaseRequest(String idempotencyKey, String email, long flashSaleId, String status, String createdAtExpr) {
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, status, created_at) " +
            "values (gen_random_uuid(), ?, (select id from users where email = ?), ?, ?, " + createdAtExpr + ")",
            idempotencyKey, email, flashSaleId, status);
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
