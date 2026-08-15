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

import java.time.Instant;
import java.util.HashMap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminFlashSaleControllerIT {

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
        return objectMapper.writeValueAsString(new HashMap<>() {{
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
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(requestBody(email, "secret123")))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private long insertProduct(String name) {
        jdbcTemplate.update("insert into products (name) values (?)", name);
        return jdbcTemplate.queryForObject("select id from products where name = ?", Long.class, name);
    }

    @Test
    void adminCanCreateAndListAFlashSale() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin@example.com");
        long productId = insertProduct("Admin-Created Product");

        String body = "{\"productId\":" + productId + ",\"salePrice\":9.99," +
            "\"startsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(7200) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":50}";

        MvcResult createResult = mockMvc.perform(post("/api/admin/flash-sales")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/admin/flash-sales").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("SCHEDULED"));
    }

    @Test
    void createRejectsAStartAfterEndWithValidationError() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin-validation@example.com");
        long productId = insertProduct("Invalid Window Product");

        String body = "{\"productId\":" + productId + ",\"salePrice\":9.99," +
            "\"startsAt\":\"" + Instant.now().plusSeconds(7200) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":50}";

        mockMvc.perform(post("/api/admin/flash-sales")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateRejectsChangingPriceOnceTheSaleIsActive() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin-active@example.com");
        long productId = insertProduct("Active Sale Product");

        jdbcTemplate.update(
            "insert into flash_sales (product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (?, 9.99, now() - interval '1 hour', now() + interval '1 hour', 1, 'SCHEDULED')", productId);
        long saleId = jdbcTemplate.queryForObject(
            "select id from flash_sales where product_id = ?", Long.class, productId);
        jdbcTemplate.update("insert into inventory (flash_sale_id, total_quantity, available_quantity) values (?, 10, 10)", saleId);

        String body = "{\"salePrice\":19.99," +
            "\"startsAt\":\"" + Instant.now().minusSeconds(3600) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":10}";

        mockMvc.perform(put("/api/admin/flash-sales/" + saleId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());
    }

    @Test
    void nonAdminUserIsForbidden() throws Exception {
        String userToken = registerAndLogin("flashsale-plain-user@example.com");

        mockMvc.perform(get("/api/admin/flash-sales").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
