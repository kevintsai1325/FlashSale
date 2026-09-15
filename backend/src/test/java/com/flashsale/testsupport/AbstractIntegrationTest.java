package com.flashsale.testsupport;

import com.flashsale.common.config.RabbitConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for every backend IT: Postgres/Redis/RabbitMQ containers are started exactly once
 * for the whole test JVM (static fields, not per-class {@code @Container} lifecycle), and every
 * subclass contributes the identical {@code @DynamicPropertySource} values. That property-set
 * uniformity is what lets Spring's test context cache reuse one ApplicationContext across every
 * subclass instead of rebuilding it (and restarting three containers) per class — the previous
 * per-class {@code @Container}/{@code @DynamicPropertySource} pattern gave every class a distinct
 * port, so the cache saw every class as needing a new context.
 *
 * <p>Because the containers (and therefore the database/Redis/RabbitMQ) now outlive any single
 * test class, {@link #resetSharedInfrastructure()} wipes all of it after every test method so the
 * next test — regardless of which subclass it belongs to — starts from a clean slate. This must
 * run in {@code @AfterEach}, not {@code @BeforeEach}: Spring applies method-level {@code @Sql}
 * fixtures during {@code SpringExtension}'s {@code BeforeEachCallback}, which JUnit invokes
 * *before* this class's own {@code @BeforeEach} methods would run — a {@code @BeforeEach} reset
 * here would wipe out the fixture data a test just asked for.
 *
 * <p>The integration-test profile disables annotation-driven scheduling and Rabbit listener
 * auto-startup. Scheduler and consumer beans remain available, so tests explicitly invoke each
 * asynchronous step and can safely share these containers without cached contexts racing for
 * messages or modifying another test's rows. Production keeps both background mechanisms enabled
 * by default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    // A handful of small, legitimate property differences between subclasses (JWT keypair
    // overrides, a disabled mail health check) mean more than one distinct ApplicationContext ends
    // up cached at once (spring.test.context.cache.maxSize=10). Each cached context keeps its own
    // HikariCP pool (default size 10) open against this single shared container for as long as it
    // stays cached, so several simultaneously-cached contexts can exceed Postgres's default
    // max_connections (100). Raise the ceiling generously.
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withCommand("postgres", "-c", "max_connections=300");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine");
    // P4 步驟 3：領域事件走 Kafka。沒有它的話，Kafka-bound 的 outbox 列會發佈失敗、
    // 永遠停在未發佈 —— 而「outbox 全部發佈完成」是既有的驗收條件之一。
    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void sharedContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @AfterEach
    void resetSharedInfrastructure() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                order_status_history, payment_records, order_items, orders,
                inventory, flash_sales, refresh_tokens, notification_deliveries, products, users,
                outbox_events, consumed_messages, api_audit_logs
            RESTART IDENTITY CASCADE
            """);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        rabbitAdmin.purgeQueue(RabbitConfig.CREATE_ORDER_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.CREATE_ORDER_DLQ, false);
        rabbitAdmin.purgeQueue(RabbitConfig.STOCK_RELEASE_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.STOCK_RELEASE_DLQ, false);
        // P4：終態回寫給 purchase-service 的佇列。backend 也宣告它（見 RabbitConfig 的註解），
        // 所以測試之間必須一起清乾淨，否則上一個測試留下的終態事件會漏到下一個測試。
        rabbitAdmin.purgeQueue(RabbitConfig.PURCHASE_RESOLVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.PURCHASE_RESOLVED_DLQ, false);
    }
}
