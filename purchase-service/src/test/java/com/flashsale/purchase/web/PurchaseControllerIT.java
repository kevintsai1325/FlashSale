package com.flashsale.purchase.web;

import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P4 步驟 1 刪掉的那個 PurchaseControllerIT，在這裡以等值的形式回來 ——
 * 它打的是 purchase-service 自己的端點、自己的 schema，不再需要 backend 在場。
 *
 * 這是這個服務的**同步那一段**的完整驗收：限購、冪等、售罄，以及「有沒有把建單請求
 * 寫進自己的 outbox」。非同步的後半段（建單）跨到另一個服務，不在這裡的涵蓋範圍內。
 */
class PurchaseControllerIT extends AbstractIntegrationTest {

    private static final long FLASH_SALE_ID = 1L;
    private static final long BUYER_ID = 42L;

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void stubTheFlashSale() {
        Instant now = Instant.now();
        when(flashSaleClient.fetch(anyLong())).thenReturn(new FlashSaleSnapshot(
            FLASH_SALE_ID, 7L, new BigDecimal("9.99"),
            now.minus(1, ChronoUnit.MINUTES), now.plus(1, ChronoUnit.HOURS), 1));
        when(flashSaleClient.availableQuantity(anyLong())).thenReturn(10);
    }

    private MvcResult purchase(long userId, String idempotencyKey, int quantity) throws Exception {
        return mockMvc.perform(post("/api/flash-sales/{id}/purchase-requests", FLASH_SALE_ID)
                .with(jwt().jwt(jwt -> jwt.claim("userId", userId).claim("role", "USER")))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content("{\"quantity\":" + quantity + "}"))
            .andExpect(status().isAccepted())
            .andReturn();
    }

    private String statusOf(MvcResult result) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
            .readTree(result.getResponse().getContentAsString()).get("status").asText();
    }

    @Test
    void acceptsAPurchaseAndWritesBothTheRequestRowAndTheOutboxEvent() throws Exception {
        MvcResult result = purchase(BUYER_ID, "key-1", 1);
        assertThat(statusOf(result)).isEqualTo("PENDING");

        Integer requests = jdbcTemplate.queryForObject(
            "select count(*) from purchase_requests where user_id = ? and status = 'PENDING'", Integer.class, BUYER_ID);
        assertThat(requests).isEqualTo(1);

        // 建單請求與搶購請求必須在同一個交易裡落地 —— 這正是 outbox 模式存在的理由，
        // 而且這張表現在真的屬於這個服務（步驟 1 時它在 backend 的資料庫裡）。
        String payload = jdbcTemplate.queryForObject(
            "select payload::text from outbox_events where event_type = 'CreateOrderRequested'", String.class);
        String requestId = jdbcTemplate.queryForObject(
            "select request_id::text from purchase_requests where user_id = ?", String.class, BUYER_ID);
        assertThat(payload)
            .contains("\"purchaseRequestId\": \"" + requestId + "\"")
            .contains("\"flashSaleId\": " + FLASH_SALE_ID);

        // Redis 的可售數量被扣了一格。
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void replayingTheSameIdempotencyKeyReturnsTheSameRequestWithoutReservingAgain() throws Exception {
        MvcResult first = purchase(BUYER_ID, "key-replay", 1);
        MvcResult second = purchase(BUYER_ID, "key-replay", 1);

        assertThat(second.getResponse().getContentAsString())
            .isEqualTo(first.getResponse().getContentAsString());
        Integer requests = jdbcTemplate.queryForObject(
            "select count(*) from purchase_requests where user_id = ?", Integer.class, BUYER_ID);
        assertThat(requests).isEqualTo(1);
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void aSecondPurchaseByTheSameBuyerIsRejectedOnceTheFirstOneSucceeded() throws Exception {
        purchase(BUYER_ID, "key-a", 1);
        jdbcTemplate.update("update purchase_requests set status = 'SUCCEEDED', order_id = 5001 where user_id = ?", BUYER_ID);

        MvcResult second = purchase(BUYER_ID, "key-b", 1);

        assertThat(statusOf(second)).isEqualTo("REJECTED");
        // 被限購擋下的請求不得動到庫存：第一筆扣了一格，這一筆一格都不能再扣。
        assertThat(stock()).isEqualTo(9);
    }

    @Test
    void reportsSoldOutOnceRedisRunsOut() throws Exception {
        when(flashSaleClient.availableQuantity(anyLong())).thenReturn(1);

        assertThat(statusOf(purchase(1L, "k1", 1))).isEqualTo("PENDING");
        assertThat(statusOf(purchase(2L, "k2", 1))).isEqualTo("SOLD_OUT");
        assertThat(stock()).isZero();
    }

    @Test
    void theStatusEndpointOnlyShowsTheOwnersOwnRequest() throws Exception {
        purchase(BUYER_ID, "key-owner", 1);
        String requestId = jdbcTemplate.queryForObject(
            "select request_id::text from purchase_requests where user_id = ?", String.class, BUYER_ID);

        mockMvc.perform(get("/api/purchase-requests/{id}", requestId)
                .with(jwt().jwt(jwt -> jwt.claim("userId", BUYER_ID).claim("role", "USER"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(get("/api/purchase-requests/{id}", requestId)
                .with(jwt().jwt(jwt -> jwt.claim("userId", 999L).claim("role", "USER"))))
            .andExpect(status().isNotFound());
    }

    private long stock() {
        String value = redisStock();
        return value == null ? -1 : Long.parseLong(value);
    }

    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    private String redisStock() {
        return redis.opsForValue().get("stock:" + FLASH_SALE_ID);
    }
}
