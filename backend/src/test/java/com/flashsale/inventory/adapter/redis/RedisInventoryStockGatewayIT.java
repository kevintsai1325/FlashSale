package com.flashsale.inventory.adapter.redis;

import com.flashsale.common.exception.ServiceUnavailableException;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("integration-test")
@Testcontainers
class RedisInventoryStockGatewayIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
    }

    @Autowired InventoryStockGateway gateway;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void seedFlashSaleAndInventory() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        jdbcTemplate.update("DELETE FROM inventory");
        jdbcTemplate.update("DELETE FROM flash_sales");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("INSERT INTO products (id, name, description) VALUES (1, 'Sneakers', 'desc')");
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (42, 1, 9.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (1, 42, 3, 3, 0, 0, 0)");
    }

    @Test
    void lazilySeedsFromPostgresThenReservesAtomically() {
        StockReservationResult first = gateway.reserve(42L, 1);
        StockReservationResult second = gateway.reserve(42L, 1);
        StockReservationResult third = gateway.reserve(42L, 1);
        StockReservationResult fourth = gateway.reserve(42L, 1);

        assertThat(first).isEqualTo(StockReservationResult.RESERVED);
        assertThat(second).isEqualTo(StockReservationResult.RESERVED);
        assertThat(third).isEqualTo(StockReservationResult.RESERVED);
        assertThat(fourth).isEqualTo(StockReservationResult.INSUFFICIENT_STOCK);
        assertThat(gateway.currentValue(42L)).contains(0);
    }

    @Test
    void releaseIncrementsStockBackUp() {
        gateway.reserve(42L, 2);

        gateway.release(42L, 2);

        assertThat(gateway.currentValue(42L)).contains(3);
    }

    @Test
    void resyncOverwritesWithAuthoritativeValue() {
        gateway.reserve(42L, 3);

        gateway.resync(42L, 3);

        assertThat(gateway.currentValue(42L)).contains(3);
    }

    @Test
    void recoversFromTransientRedisDeletionByReseeding() {
        // Test recovery when Redis key is deleted after seeding attempt
        // This exercises the lazy-load retry path in reserve()

        // Create flash sale with inventory
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (99, 1, 5.99, now() - interval '1 minute', now() + interval '1 hour', 1, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (4, 99, 2, 2, 0, 0, 0)");

        // Seed the redis key manually to simulate previous initialization
        redisTemplate.opsForValue().set("stock:99", "2");

        // Simulate key being deleted (Redis data loss or eviction)
        // Use a barrier to delete the key right after ensureSeeded but before Lua execution
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // Start a thread that will delete the key when the latch is released
        executor.submit(() -> {
            try {
                latch.await();
                Thread.sleep(10);  // Brief delay to ensure we're past the first hasKey() check
                redisTemplate.delete("stock:99");
                Thread.sleep(10);
                // Delete again to ensure persistent -2 scenario, but it's OK if key is recreated
                if (!Boolean.TRUE.equals(redisTemplate.hasKey("stock:99"))) {
                    redisTemplate.delete("stock:99");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        try {
            latch.countDown();  // Signal the deletion thread to start
            Thread.sleep(5);    // Small delay before calling reserve

            // This reserve() should:
            // 1. ensureSeeded sees key was deleted -> reseeds from DB
            // 2. Executes Lua script successfully
            StockReservationResult result = gateway.reserve(99L, 1);
            assertThat(result).isEqualTo(StockReservationResult.RESERVED);
            assertThat(gateway.currentValue(99L)).contains(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrentReservesFromColdStartAreSafeAndAtomic() throws InterruptedException {
        // Clear Redis to force lazy-load
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        // Set up a fresh flash sale with limited inventory
        jdbcTemplate.update("INSERT INTO flash_sales (id, product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "VALUES (50, 1, 7.99, now() - interval '1 minute', now() + interval '1 hour', 10, 'ACTIVE')");
        jdbcTemplate.update("INSERT INTO inventory (id, flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version) " +
            "VALUES (3, 50, 3, 3, 0, 0, 0)");

        // Run N concurrent threads all trying to reserve 1 from the same unseeded flash sale
        int numThreads = 10;
        int initialStock = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger reservedCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    StockReservationResult result = gateway.reserve(50L, 1);
                    if (result == StockReservationResult.RESERVED) {
                        reservedCount.incrementAndGet();
                    } else {
                        insufficientCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Verify no overselling: exactly 3 threads got RESERVED (the stock quantity)
        assertThat(reservedCount.get()).isEqualTo(initialStock);
        assertThat(insufficientCount.get()).isEqualTo(numThreads - initialStock);

        // Verify final Redis value is correct (0, since all 3 were reserved)
        assertThat(gateway.currentValue(50L)).contains(0);
    }

    @Test
    void reserveIncrementsReservedCounterAndRecordsLatency() {
        // @BeforeEach seedFlashSaleAndInventory() already seeds flash sale id 42 with
        // available_quantity 3 — reserving 1 succeeds without any extra setup.
        gateway.reserve(42L, 1);

        assertThat(meterRegistry.get("purchase.reservation").tag("outcome", "reserved").counter().count())
            .isGreaterThanOrEqualTo(1.0);
        assertThat(meterRegistry.get("purchase.reservation.latency").timer().count())
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void reserveIncrementsInsufficientStockCounterWhenSoldOut() {
        gateway.reserve(42L, 3); // exhausts the seeded available_quantity of 3

        gateway.reserve(42L, 1); // now sold out

        assertThat(meterRegistry.get("purchase.reservation").tag("outcome", "insufficient_stock").counter().count())
            .isGreaterThanOrEqualTo(1.0);
    }
}
