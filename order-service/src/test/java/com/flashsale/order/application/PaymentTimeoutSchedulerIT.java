package com.flashsale.order.application;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;
@Sql("/db/testdata/inventory-fixtures.sql")
class PaymentTimeoutSchedulerIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PaymentTimeoutScheduler scheduler;

    @Test
    void overduePendingPaymentOrderIsExpiredAndInventoryReleased() {
        jdbcTemplate.update(
            "insert into orders (id, order_no, user_id, total_amount, status, payment_due_at, flash_sale_id) " +
            "values (883, 'ORD-883', 997, 9.99, 'PENDING_PAYMENT', now() - interval '1 minute', 1)");
        jdbcTemplate.update("insert into order_items (order_id, product_id, product_name, quantity, unit_price) values (883, 1, 'Payment Timeout Test Product', 1, 9.99)");
        jdbcTemplate.update("update inventory set available_quantity = 0, sold_quantity = 1 where flash_sale_id = 1");

        scheduler.expireOverduePayments();

        String status = jdbcTemplate.queryForObject("select status from orders where id = 883", String.class);
        assertThat(status).isEqualTo("EXPIRED");

        Integer available = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(available).isEqualTo(1);

        Integer historyCount = jdbcTemplate.queryForObject(
            "select count(*) from order_status_history where order_id = 883 and to_status = 'EXPIRED'", Integer.class);
        assertThat(historyCount).isEqualTo(1);
    }
}
