package com.flashsale.testsupport;

import com.flashsale.admin.adapter.http.AnalyticsClient;
import com.flashsale.common.client.OrderServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * <p>The integration-test profile disables annotation-driven scheduling. Scheduler and consumer beans remain available, so tests explicitly invoke each
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

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void sharedContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    /**
     * P5：**platform 的整合測試停在自己的行程邊界**，所有跨服務的 client 一律 mock。
     *
     * 放在共用底座而不是每個測試各自宣告，有兩個理由：一是少了它，任何一個測試只要
     * 碰到店面或後台，就會去連一個不存在的 order-service 並以 Connection refused 收場；
     * 二是所有測試共用同一組 mock 定義，Spring 的 context 快取才不會被拆成好幾份。
     *
     * 預設值是「空的、可用的」——需要具體資料的測試自己再 stub 一次。
     */
    @MockBean protected OrderServiceClient orderServiceClient;
    @MockBean protected AnalyticsClient analyticsClient;

    @BeforeEach
    void stubCrossServiceClients() {
        org.mockito.Mockito.when(orderServiceClient.inventories(org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Map.of());
        org.mockito.Mockito.when(orderServiceClient.declareInventory(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
            .thenAnswer(invocation -> new OrderServiceClient.InventoryView(
                invocation.getArgument(1), invocation.getArgument(1), 0, 0));
        org.mockito.Mockito.when(orderServiceClient.orders(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
            .thenReturn(new OrderServiceClient.PagedOrders(java.util.List.of(), 0, 0, 20));
        org.mockito.Mockito.when(orderServiceClient.orderDetail(org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(analyticsClient.summary())
            .thenReturn(new AnalyticsClient.DashboardSummary(0, 0, java.util.Map.of(), java.math.BigDecimal.ZERO));
        org.mockito.Mockito.when(analyticsClient.trends(org.mockito.ArgumentMatchers.any()))
            .thenReturn(new AnalyticsClient.DashboardTrends(java.util.List.of(), java.util.List.of()));
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate redisTemplate;

    @AfterEach
    void resetSharedInfrastructure() {
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                flash_sales, refresh_tokens, notification_deliveries, products, users, api_audit_logs
            RESTART IDENTITY CASCADE
            """);
        // P5 之後 platform 不收發任何訊息，所以這裡沒有佇列要清 —— Redis 仍然要清，
        // 排程的分散式鎖住在那裡。
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
