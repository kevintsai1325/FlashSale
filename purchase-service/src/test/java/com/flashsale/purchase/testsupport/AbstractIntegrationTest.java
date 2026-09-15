package com.flashsale.purchase.testsupport;

import com.flashsale.purchase.adapter.http.CachingFlashSaleClient;
import com.flashsale.purchase.config.RabbitConfig;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * purchase-service 的整合測試共用底座。P4 步驟 1 時這裡是空的 —— 那時這個服務沒有自己的
 * schema，整合測試無從寫起，只能靠 k6 端到端壓測守著「不超賣」。步驟 2 把 schema 搬過來之後，
 * 那個缺口才補得起來。
 *
 * 容器是 static 的、整個測試 JVM 只起一次，每個子類別貢獻完全相同的
 * {@code @DynamicPropertySource}，這樣 Spring 的測試 context 快取才會重用同一個
 * ApplicationContext（與 backend 那側同一套理由，見它的 AbstractIntegrationTest）。
 *
 * {@link CachingFlashSaleClient} 是 mock 的：真的去呼叫 backend 等於在 purchase-service 的
 * 測試裡跑另一個服務。**這正是拆分後整合測試的邊界** —— 一個服務的整合測試只能涵蓋
 * 到自己的行程邊緣，跨服務的那一段由契約與端到端壓測負責。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    protected static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static {
        POSTGRES.start();
        REDIS.start();
        RABBITMQ.start();
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
    }

    @MockBean protected CachingFlashSaleClient flashSaleClient;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;

    @AfterEach
    void resetSharedInfrastructure() {
        jdbcTemplate.execute("TRUNCATE TABLE purchase_requests, outbox_events RESTART IDENTITY");
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        rabbitAdmin.purgeQueue(RabbitConfig.PURCHASE_RESOLVED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.PURCHASE_RESOLVED_DLQ, false);
    }
}
