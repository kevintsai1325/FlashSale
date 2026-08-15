package com.flashsale.notification.application;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

// Real Mailpit isn't reachable from this test process (compose.yaml never publishes it to the
// host - see README.md "Viewing sent emails" section), so spring.mail.host/port here just mirror
// the convention already used by AuthControllerRegisterIT/PaymentTimeoutSchedulerIT/
// InventoryReconciliationSchedulerIT (none of which assert on delivery outcome). To get a
// deterministic SENT/FAILED outcome for this test's assertions, JavaMailSender is @MockBean'd:
// createMimeMessage() returns a real (offline) MimeMessage so Thymeleaf rendering + message
// construction runs unmodified, and send(...) is a Mockito no-op standing in for a successful
// SMTP handoff.
class NotificationRetryServiceIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void mailHealthProps(DynamicPropertyRegistry registry) {
        // @MockBean below replaces the real JavaMailSenderImpl with a Mockito mock, which breaks
        // Actuate's MailHealthContributorAutoConfiguration (it specifically scans for
        // JavaMailSenderImpl beans and throws "Beans must not be empty" otherwise). Not relevant
        // to this test's Spring Security-gated non-actuator context anyway.
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @MockBean
    JavaMailSender mailSender;

    @Autowired
    NotificationRetryService retryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void retrySucceedsAndDoesNotInsertNewRow() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (5001, 'retry-user@example.com', 'x', 'USER', 'ACTIVE')");
        Long deliveryId = jdbcTemplate.queryForObject(
            "insert into notification_deliveries (user_id, channel, template, recipient, status, attempt_count) " +
            "values (5001, 'EMAIL', 'registration-success', 'retry-user@example.com', 'FAILED', 1) returning id",
            Long.class);

        Integer countBefore = jdbcTemplate.queryForObject(
            "select count(*) from notification_deliveries", Integer.class);

        retryService.retry(deliveryId);

        Integer countAfter = jdbcTemplate.queryForObject(
            "select count(*) from notification_deliveries", Integer.class);
        assertThat(countAfter).isEqualTo(countBefore);

        String status = jdbcTemplate.queryForObject(
            "select status from notification_deliveries where id = ?", String.class, deliveryId);
        assertThat(status).isEqualTo("SENT");
    }

    @Test
    void retryUnknownIdThrowsNotFound() {
        assertThatThrownBy(() -> retryService.retry(999_999L))
            .isInstanceOf(NotFoundException.class);
    }
}
