package com.flashsale.order.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class OrderCancelIT {

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

    private String registerAndLogin(String email) throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("email", email);
            put("password", "secret123");
        }});
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @Sql("/db/testdata/inventory-fixtures.sql")
    void cancellingAPendingOrderReleasesInventoryAndWritesCompensationEvent() throws Exception {
        String token = registerAndLogin("cancel-test@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (777, 'ORD-777', (select id from users where email = 'cancel-test@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, quantity, unit_price) values (777, 1, 1, 9.99)");
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status) " +
            "values (gen_random_uuid(), 'cancel-key', (select id from users where email = 'cancel-test@example.com'), 1, 777, 'SUCCEEDED')");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        mockMvc.perform(post("/api/orders/777/cancel").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);

        Integer releaseEventCount = jdbcTemplate.queryForObject(
            "select count(*) from outbox_events where event_type = 'StockReleaseRequested'", Integer.class);
        assertThat(releaseEventCount).isEqualTo(1);
    }

    @Test
    void cancellingSomeoneElsesOrderIsNotFoundNotForbidden() throws Exception {
        String owner = registerAndLogin("owner@example.com");
        String stranger = registerAndLogin("stranger@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (778, 'ORD-778', (select id from users where email = 'owner@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");

        mockMvc.perform(post("/api/orders/778/cancel").header("Authorization", "Bearer " + stranger))
            .andExpect(status().isNotFound());
    }
}
