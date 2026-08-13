package com.flashsale.inventory.adapter.redis;

import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
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

import static org.assertj.core.api.Assertions.assertThat;

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
}
