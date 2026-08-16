package com.flashsale.notification.application;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// Same JavaMailSender @MockBean rationale as NotificationRetryServiceIT: real Mailpit isn't
// reachable from this test process, so mocking gives a deterministic SENT outcome for the "due"
// row to assert against. management.health.mail.enabled=false works around Actuate's
// MailHealthContributorAutoConfiguration otherwise failing context startup when JavaMailSender
// isn't a real JavaMailSenderImpl.
@TestPropertySource(properties = "management.health.mail.enabled=false")
class NotificationRetrySchedulerIT extends AbstractIntegrationTest {

    @MockBean
    JavaMailSender mailSender;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    NotificationRetryScheduler scheduler;

    @Test
    void dueRowRetriedNotDueAndAtCapRowsUntouched() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values " +
            "(6001, 'due@example.com', 'x', 'USER', 'ACTIVE'), " +
            "(6002, 'notdue@example.com', 'x', 'USER', 'ACTIVE'), " +
            "(6003, 'atcap@example.com', 'x', 'USER', 'ACTIVE')");

        // Due: FAILED, attempt_count = 1 (backoff = 1 minute), last attempted 10 minutes ago.
        Long dueId = jdbcTemplate.queryForObject(
            "insert into notification_deliveries (user_id, channel, template, recipient, status, attempt_count, updated_at) " +
            "values (6001, 'EMAIL', 'registration-success', 'due@example.com', 'FAILED', 1, now() - interval '10 minutes') returning id",
            Long.class);

        // Not due: FAILED, attempt_count = 2 (backoff = 5 minutes), last attempted just now.
        // (Deliberately attempt_count=2, not 1: the scheduler's own fixedDelay is also 1 minute,
        // so an attempt_count=1/1-minute-backoff row would race against this test's own polling
        // window and could flip to due partway through. 5 minutes safely outlasts the test.)
        Long notDueId = jdbcTemplate.queryForObject(
            "insert into notification_deliveries (user_id, channel, template, recipient, status, attempt_count, updated_at) " +
            "values (6002, 'EMAIL', 'registration-success', 'notdue@example.com', 'FAILED', 2, now()) returning id",
            Long.class);

        // At cap: FAILED, attempt_count = 3 (== MAX_ATTEMPTS), long overdue by backoff but
        // excluded because it's at the attempt cap - this row is the dead-letter record.
        Long atCapId = jdbcTemplate.queryForObject(
            "insert into notification_deliveries (user_id, channel, template, recipient, status, attempt_count, updated_at) " +
            "values (6003, 'EMAIL', 'registration-success', 'atcap@example.com', 'FAILED', 3, now() - interval '1 day') returning id",
            Long.class);

        scheduler.retryDueNotifications();

        String dueStatus = jdbcTemplate.queryForObject(
            "select status from notification_deliveries where id = ?", String.class, dueId);
        assertThat(dueStatus).isEqualTo("SENT");

        String notDueStatus = jdbcTemplate.queryForObject(
            "select status from notification_deliveries where id = ?", String.class, notDueId);
        Integer notDueAttempts = jdbcTemplate.queryForObject(
            "select attempt_count from notification_deliveries where id = ?", Integer.class, notDueId);
        assertThat(notDueStatus).isEqualTo("FAILED");
        assertThat(notDueAttempts).isEqualTo(2);

        String atCapStatus = jdbcTemplate.queryForObject(
            "select status from notification_deliveries where id = ?", String.class, atCapId);
        Integer atCapAttempts = jdbcTemplate.queryForObject(
            "select attempt_count from notification_deliveries where id = ?", Integer.class, atCapId);
        assertThat(atCapStatus).isEqualTo("FAILED");
        assertThat(atCapAttempts).isEqualTo(3);
    }
}
