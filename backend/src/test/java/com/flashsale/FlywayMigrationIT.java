package com.flashsale;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 這份表清單是 platform **還擁有什麼**的權威說明。
 *
 * 用 containsExactlyInAnyOrder 而不是 contains：拆分的每一步都會讓這個清單變短，
 * 而「少了一張表沒人發現」與「多了一張本該搬走的表」一樣危險 —— 前者是漏刪，
 * 後者是搬了一半。精確比對讓兩種情況都必須被顯式確認。
 */
class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void platformOwnsExactlyItsOwnTables() {
        var tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

        // orders / order_items / order_status_history / payment_records / inventory → order-service（P5）
        // purchase_requests → purchase-service（P4 步驟 2）
        // outbox_events / consumed_messages → 隨著訊息流程一起搬走，platform 不再收發任何訊息
        assertThat(tables).containsExactlyInAnyOrder(
            "users", "refresh_tokens", "products", "flash_sales",
            "notification_deliveries", "api_audit_logs", "flyway_schema_history"
        );
    }
}
