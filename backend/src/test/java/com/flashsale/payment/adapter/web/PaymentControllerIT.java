package com.flashsale.payment.adapter.web;

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

class PaymentControllerIT extends AbstractIntegrationTest {

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
    void successfulSimulatedPaymentMarksOrderPaid() throws Exception {
        String token = registerAndLogin("pay-success@example.com");
        jdbcTemplate.update("insert into products (id, name) values (2, 'Payment Test Product')");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (881, 'ORD-881', (select id from users where email = 'pay-success@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (881, 2, 'Payment Test Product', 1, 9.99)");

        mockMvc.perform(post("/api/orders/881/payments").contentType(APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("{\"result\":\"SUCCESS\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"));

        Integer paymentRecordCount = jdbcTemplate.queryForObject(
            "select count(*) from payment_records where order_id = 881 and result = 'SUCCESS'", Integer.class);
        assertThat(paymentRecordCount).isEqualTo(1);

        Integer historyCount = jdbcTemplate.queryForObject(
            "select count(*) from order_status_history where order_id = 881 and from_status = 'PENDING_PAYMENT' and to_status = 'PAID'",
            Integer.class);
        assertThat(historyCount).isEqualTo(1);
    }

    @Test
    @Sql("/db/testdata/inventory-fixtures.sql")
    void failedSimulatedPaymentCancelsOrderAndReleasesInventory() throws Exception {
        String token = registerAndLogin("pay-fail@example.com");
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (882, 'ORD-882', (select id from users where email = 'pay-fail@example.com'), 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (882, 1, 'Payment Failure Test Product', 1, 9.99)");
        jdbcTemplate.update(
            "insert into purchase_requests (request_id, idempotency_key, user_id, flash_sale_id, order_id, status) " +
            "values (gen_random_uuid(), 'pay-fail-key', (select id from users where email = 'pay-fail@example.com'), 1, 882, 'SUCCEEDED')");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        mockMvc.perform(post("/api/orders/882/payments").contentType(APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .content("{\"result\":\"FAILURE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);
    }
}
