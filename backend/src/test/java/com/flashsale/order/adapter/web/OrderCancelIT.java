package com.flashsale.order.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCancelIT extends AbstractIntegrationTest {

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
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (777, 1, 'Order Cancel Test Product', 1, 9.99)");
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

        Integer historyCount = jdbcTemplate.queryForObject(
            "select count(*) from order_status_history where order_id = 777 and from_status = 'PENDING_PAYMENT' and to_status = 'CANCELLED'",
            Integer.class);
        assertThat(historyCount).isEqualTo(1);
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
