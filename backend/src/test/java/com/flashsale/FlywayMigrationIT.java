package com.flashsale;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void allBaselineTablesExist() {
        var tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        assertThat(tables).containsExactlyInAnyOrder(
            "users", "refresh_tokens", "products", "flash_sales", "inventory",
            "purchase_requests", "orders", "order_items", "payment_records",
            "outbox_events", "consumed_messages", "notification_deliveries",
            "api_audit_logs", "order_status_history", "flyway_schema_history"
        );
    }
}
