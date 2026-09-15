package com.flashsale.order.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 證明 {@code InventoryJpaRepository.findByFlashSaleIdForUpdate} 的悲觀鎖真的把同一列的
 * 併發消費序列化了，而不只是語法上寫著。
 *
 * P4 拆分前，這個不變量是由 PurchaseConcurrencyIT 從 HTTP 端點打進來驗證的。拆分後那條
 * 路徑橫跨兩個服務，單一服務的整合測試再也涵蓋不了它 —— 端到端那一段改由 k6 壓測的
 * 「不超賣」不變量守著（docs/portfolio/data/benchmark-results.json）。
 * 這裡留下的是**只屬於 backend 的那一半**：同一筆庫存被並發消費時，賣出的數量不會超過庫存。
 *
 * 刻意不加 @Transactional：整個測試包在單一交易裡會讓所有執行緒共用同一條連線，
 * 鎖競爭就永遠不會發生，那正好是這個測試要證明的東西的反面。
 */
@Sql("/db/testdata/concurrency-fixtures.sql")
class OrderPurchaseConsumerConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONCURRENT_CONSUMERS = 5;
    private static final int STOCK = 1;

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired OrderPurchaseConsumer consumer;

    private static final long BUYER_ID = 990L;

    // orders.user_id 有外鍵指向 users。少了這一步，五條執行緒會全部倒在外鍵約束上，
    // 看起來像「一件都沒賣出去」，而不是這個測試要證明的「只賣出一件」。
    private void seedBuyer() {
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, status) values (?, 'concurrency-it@example.com', 'x', 'USER', 'ACTIVE') on conflict (id) do nothing",
            BUYER_ID);
    }

    private Message messageFor(UUID purchaseRequestId, long outboxEventId) throws Exception {
        CreateOrderRequestedEvent event = new CreateOrderRequestedEvent(
            purchaseRequestId, BUYER_ID, 1L, 1L, 1, new BigDecimal("9.99"));
        MessageProperties properties = new MessageProperties();
        properties.setHeader("outboxEventId", outboxEventId);
        return new Message(objectMapper.writeValueAsBytes(event), properties);
    }

    @Test
    void neverSellsMoreThanTheStockUnderRealConcurrentContention() throws Exception {
        seedBuyer();
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_CONSUMERS);
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_CONSUMERS);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_CONSUMERS; i++) {
            long id = i + 1;
            tasks.add(() -> {
                // 每條執行緒都在這裡等齊，讓消費盡可能同時發生 —— 否則它們會不小心排成序列，
                // 根本碰不到列鎖。
                barrier.await(10, TimeUnit.SECONDS);
                try {
                    consumer.handle(messageFor(new UUID(0L, id), 1000L + id));
                    return true;
                } catch (RuntimeException expectedWhenStockIsGone) {
                    // 庫存賣完之後，剩下的消費會撞上「Redis/Postgres 庫存漂移」這個防護，
                    // 訊息進 DLQ 由補償路徑處理。這裡要確認的是它沒有默默賣出去。
                    return false;
                }
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        executor.shutdown();

        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                succeeded++;
            }
        }

        assertThat(succeeded).as("成功建單的數量必須剛好等於庫存，不能更多").isEqualTo(STOCK);

        Integer remainingStock = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(remainingStock).as("庫存不得變成負數，也不得重複扣減").isEqualTo(0);

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertThat(orderCount).as("訂單數必須剛好等於庫存").isEqualTo(STOCK);
    }
}
