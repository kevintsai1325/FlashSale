package com.flashsale.purchase.adapter.http;

import com.flashsale.purchase.application.FlashSaleClient;
import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.exception.NotFoundException;
import com.flashsale.purchase.exception.ServiceUnavailableException;
import com.flashsale.purchase.metrics.PurchaseMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 這個類別是「backend 掛掉時搶購 API 會怎麼壞」的定義，所以每一種壞法都要有一條測試。
 * 用可控的時鐘而不是 sleep：TTL 與寬限期的邊界是這裡唯一值得測的東西。
 */
class CachingFlashSaleClientTest {

    private static final FlashSaleSnapshot SALE = new FlashSaleSnapshot(10L, 1L, "item", new BigDecimal("9.99"),
        Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"), 3);

    private final PurchaseMetrics metrics = new PurchaseMetrics(new SimpleMeterRegistry());
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-14T01:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private CachingFlashSaleClient clientOver(FlashSaleClient delegate) {
        return new CachingFlashSaleClient(delegate, metrics, Duration.ofSeconds(2), Duration.ofSeconds(60), clock);
    }

    private static FlashSaleClient delegateThat(java.util.function.Function<Long, FlashSaleSnapshot> fetch) {
        return new FlashSaleClient() {
            @Override public FlashSaleSnapshot fetch(Long flashSaleId) { return fetch.apply(flashSaleId); }
            @Override public int availableQuantity(Long flashSaleId) { return 0; }
        };
    }

    @Test
    void servesFromCacheWithinTheTtlWithoutCallingTheDelegateAgain() {
        AtomicInteger calls = new AtomicInteger();
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            calls.incrementAndGet();
            return SALE;
        }));

        client.fetch(10L);
        now.set(now.get().plusMillis(1_999));
        client.fetch(10L);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refetchesOnceTheTtlHasPassed() {
        AtomicInteger calls = new AtomicInteger();
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            calls.incrementAndGet();
            return SALE;
        }));

        client.fetch(10L);
        now.set(now.get().plusSeconds(3));
        client.fetch(10L);

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void servesStaleInsideTheGraceWindowWhenTheDelegateIsDown() {
        AtomicInteger calls = new AtomicInteger();
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            if (calls.incrementAndGet() == 1) {
                return SALE;
            }
            throw new ServiceUnavailableException("FLASH_SALE_LOOKUP_UNAVAILABLE", "down");
        }));

        client.fetch(10L);
        now.set(now.get().plusSeconds(30));

        assertThat(client.fetch(10L)).isEqualTo(SALE);
    }

    @Test
    void rejectsOnceTheGraceWindowHasPassed() {
        AtomicInteger calls = new AtomicInteger();
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            if (calls.incrementAndGet() == 1) {
                return SALE;
            }
            throw new ServiceUnavailableException("FLASH_SALE_LOOKUP_UNAVAILABLE", "down");
        }));

        client.fetch(10L);
        now.set(now.get().plusSeconds(61));

        assertThatThrownBy(() -> client.fetch(10L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void rejectsImmediatelyWhenThereIsNothingCachedToFallBackOn() {
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            throw new ServiceUnavailableException("FLASH_SALE_LOOKUP_UNAVAILABLE", "down");
        }));

        assertThatThrownBy(() -> client.fetch(10L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void doesNotServeStaleWhenTheSaleIsAuthoritativelyGone() {
        AtomicInteger calls = new AtomicInteger();
        CachingFlashSaleClient client = clientOver(delegateThat(id -> {
            if (calls.incrementAndGet() == 1) {
                return SALE;
            }
            throw new NotFoundException("FLASH_SALE_NOT_FOUND", "gone");
        }));

        client.fetch(10L);
        now.set(now.get().plusSeconds(3));

        assertThatThrownBy(() -> client.fetch(10L)).isInstanceOf(NotFoundException.class);
        // 快取也要被清掉，否則下一次下游短暫失敗時會把已刪除的活動當成可用的過期資料放行。
        now.set(now.get().plusSeconds(1));
        assertThatThrownBy(() -> client.fetch(10L)).isInstanceOf(NotFoundException.class);
    }
}
