package com.flashsale.payment.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * P5：這個服務不簽發 token（私鑰在 platform），也沒有 /api/auth 端點。
     * 測試改用 Spring Security Test 的 jwt() post-processor 直接塞一個已驗證的 principal ——
     * 那條路徑不經過 JwtDecoder，測的是「這個服務怎麼看待一個已通過驗證的使用者」，
     * 而那正是它現在唯一該負責的事。
     */
    private static RequestPostProcessor asUser(long userId) {
        return jwt().jwt(jwt -> jwt.claim("userId", userId).claim("role", "USER"));
    }

    @Test
    void successfulSimulatedPaymentMarksOrderPaid() throws Exception {
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at) " +
            "values (881, 'ORD-881', 500, 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes')");
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (881, 2, 'Payment Test Product', 1, 9.99)");

        mockMvc.perform(post("/api/orders/881/payments").contentType(APPLICATION_JSON)
                .with(asUser(500L))
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
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at, flash_sale_id) " +
            "values (882, 'ORD-882', 500, 9.99, 'PENDING_PAYMENT', now() + interval '15 minutes', 1)");
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (882, 1, 'Payment Failure Test Product', 1, 9.99)");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        mockMvc.perform(post("/api/orders/882/payments").contentType(APPLICATION_JSON)
                .with(asUser(500L))
                .content("{\"result\":\"FAILURE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);
    }
}
