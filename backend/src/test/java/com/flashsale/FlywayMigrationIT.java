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
            "orders", "order_items", "payment_records",
            "outbox_events", "consumed_messages", "notification_deliveries",
            "api_audit_logs", "order_status_history", "flyway_schema_history"
        );
    }

    @Test
    void outboxTraceContextIsNullableJsonb() {
        var column = jdbcTemplate.queryForMap("""
            SELECT data_type, is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'outbox_events'
              AND column_name = 'trace_context'
            """);

        assertThat(column.get("data_type")).isEqualTo("jsonb");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void orderItemsHaveRequiredProductNameSnapshot() {
        var column = jdbcTemplate.queryForMap("""
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'order_items'
              AND column_name = 'product_name'
            """);

        assertThat(column.get("is_nullable")).isEqualTo("NO");
    }
}
