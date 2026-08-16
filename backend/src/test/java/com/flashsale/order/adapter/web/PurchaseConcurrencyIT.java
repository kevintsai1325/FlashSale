package com.flashsale.order.adapter.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.common.config.RabbitConfig;
import com.flashsale.common.messaging.OutboxPublisher;
import com.flashsale.order.adapter.messaging.OrderPurchaseConsumer;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Proves the pessimistic-write lock in {@code InventoryJpaRepository.findByFlashSaleIdForUpdate}
 * actually serializes concurrent purchases against the same inventory row, rather than merely
 * being syntactically present. {@link PurchaseControllerIT} exercises the purchase flow
 * sequentially (one request at a time) and cannot exercise real lock contention — this class
 * fires genuinely concurrent HTTP requests (no {@code @Transactional} on the test itself, so each
 * request commits through its own real transaction, exactly like separate browser tabs would) and
 * asserts the database ends up consistent: exactly as many orders as there was stock, never more.
 *
 * <p>Not {@code @Transactional}: wrapping the test in a single transaction would serialize every
 * {@code MockMvc} call onto the same connection/transaction, which would make lock contention
 * impossible to observe (the opposite of what this test needs to prove). Fixture rows are
 * committed directly by {@code @Sql} and left in place; this class uses its own dedicated
 * Testcontainers Postgres instance, so there's nothing to clean up between runs.
 */
@Sql("/db/testdata/concurrency-fixtures.sql")
class PurchaseConcurrencyIT extends AbstractIntegrationTest {

    private static final int CONCURRENT_BUYERS = 5;
    private static final int STOCK = 1;

    // Test-only RSA key pair (same as other web ITs), needed only because JwtKeyConfig
    // requires JWT_PRIVATE_KEY/JWT_PUBLIC_KEY to build the app context.
    private static final String TEST_PRIVATE_KEY = """
        -----BEGIN PRIVATE KEY-----
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC4Y9PChpJUXfJi
        EKskFLGDQ1AABZoj6OTbUXa0KyTUc5Y67oDW6YogCYsf562xnQHRwOLD1KNaXj06
        hKMDmC8HQL0GBDF6FTCR83uvyqfUJ4Hpssp8YQov29EK6XmvtzCgJnuqAmA8J8CX
        W3ghiURk0cuAEM5FcZJpS8Ff3sgq/iNR/TOoOmlLoF87tCapz7eAAvO1p0TkB/wr
        OklOEM8YNs+3Kz4hkKjddXz4TP6nJ+4yh9E7KlLhwaF3KkSRKgEhFJGrTDW97Ecj
        wMRsjKwTWOKED1eS6E5hXsg5JjBMTgYqLkME8gRH5jW+os71syfOj5Ao6Nc7DyFZ
        oUj3Q1pfAgMBAAECggEAB0Rv+S/Cnq6hOfo8NIzYPjst8QJHg/jO5FH+orU8m17+
        4c26qD3GIuMdZ6GC+AgfJTw788nyskIama7WmfKqj9eeW5lYtd4V7vqwukn7eWIh
        Pau9TU+pzh8UyyBOmn1W3kkGALpdPqG2doC1aGT3nB2krqR67MPAKIRC19t4+jC+
        3HPVLiIdMy2ayF3xOoTVKAxJjGIKTGSPbEZu8ANOjeWBZ6xTgjYa0pTGgFzy5yg3
        hleW0X6htbcik5cOhwEH0+zhmXGNzF4ktbSihOU+fyTCdL/QpsUPtbImHM/Gf2GT
        NMKI37R1uErJcYzLIQNB8srehWyqtAbRd8Pj0zNxoQKBgQDng7TUMa8uBJqSOh/M
        fX/S8SN3oS7EYdGeChapm2rjfxiuevF17aaXyfz8vBMxw7S26Eifl9IkPI+X4iGH
        jSaHT9RSW4LGLMQq2t7t2yEFv9BE7U7WRecfUewHg5Th3yR3XTTjG0k68jFiuVI9
        pBJrWTzumYJLQAcmVa+j7dWT0QKBgQDL5DdcylyEluyhf/iw8oZvbCQCZ/Y0WX4K
        y2xywcmkhDMcddXDMaxRbeYCQNbc1al13zJ98bIN550+u20GjIgAgvu16eMUyllC
        2nCh/MeVozE9mbLJyoF7sjM+FjXljZBgFj3lSltwW+ZnwDY8gBJrLvkapJdlKPdh
        Vnd7svqHLwKBgDwnGGDZ1+5Y++BqgcCcCw4/4TtAAeq8j75EWMcQvqEFcOBEyWAe
        s15U+Qqhw0r20omDqPruc4c+xQBtnNCfeBdIQp5zcHMVRpLr82hRuy7HO9Hs5sL9
        vqOAoZcCNTjKxarN6OPpPwm1y+cex6OEcdS6hv5nnFb49+KZ+Nza+tdBAoGAcbEQ
        Le2pKUX/LQ7u3bxeukLS0YSnBQnh/qLwFg15IwOUfIo4aF+Kdt2RJDCDnyCFHfUX
        cqMTZi2AwTpB0SULsT1YnleNCErM+zpTFACgShB1pKPPzjXdfdwgNr6rzxThLLM6
        UGDmHAEiuTe1BodjveCzhufAg+gUCXLtrUxf5oECgYAcf3bOkoST/7m3wDArGZHi
        bxa6mDItSNNoQo45H4liID3CMD4pnUGorhZ/oTF1dWwO5Jqeg4DTufkV+lHmVECZ
        HkpcA3daUkxZ5xjGl9I9L84A2e4z2ZJKerTvR+KlLP64A70YDawGpTxO8QH6fjPG
        1sN0RkZQZpMJPN4/1lsz6g==
        -----END PRIVATE KEY-----
        """;

    private static final String TEST_PUBLIC_KEY = """
        -----BEGIN PUBLIC KEY-----
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuGPTwoaSVF3yYhCrJBSx
        g0NQAAWaI+jk21F2tCsk1HOWOu6A1umKIAmLH+etsZ0B0cDiw9SjWl49OoSjA5gv
        B0C9BgQxehUwkfN7r8qn1CeB6bLKfGEKL9vRCul5r7cwoCZ7qgJgPCfAl1t4IYlE
        ZNHLgBDORXGSaUvBX97IKv4jUf0zqDppS6BfO7Qmqc+3gALztadE5Af8KzpJThDP
        GDbPtys+IZCo3XV8+Ez+pyfuMofROypS4cGhdypEkSoBIRSRq0w1vexHI8DEbIys
        E1jihA9XkuhOYV7IOSYwTE4GKi5DBPIER+Y1vqLO9bMnzo+QKOjXOw8hWaFI90Na
        XwIDAQAB
        -----END PUBLIC KEY-----
        """;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OutboxPublisher outboxPublisher;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired OrderPurchaseConsumer consumer;

    record PurchaseSubmission(String token, String requestId, String status) {}

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
    }

    private String registerAndLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                .content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(requestBody(email, password)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("accessToken").asText();
    }

    @Test
    void onlyAsManyBuyersSucceedAsThereIsStockUnderRealConcurrentContention() throws Exception {
        // Register+login every buyer up front, sequentially — the thing under test is contention
        // on the purchase endpoint itself, not on registration/login.
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            tokens.add(registerAndLogin("buyer" + i + "@example.com", "secret123"));
        }

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_BUYERS);
        List<Callable<PurchaseSubmission>> tasks = new ArrayList<>();

        for (int i = 0; i < CONCURRENT_BUYERS; i++) {
            String token = tokens.get(i);
            String idempotencyKey = "concurrent-key-" + i;
            tasks.add(() -> {
                // Block every thread here until all CONCURRENT_BUYERS have arrived, so the
                // purchase requests fire as simultaneously as the JVM/thread pool allows —
                // this is what actually exercises row-lock contention instead of accidentally
                // running the requests one after another.
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                    .andReturn();
                assertThat(result.getResponse().getStatus())
                    .as("purchase-requests must always return 202, never a raw 5xx, even under contention")
                    .isEqualTo(202);
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                String requestId = body.get("requestId").asText();
                String initialStatus = body.get("status").asText();
                // SOLD_OUT is already terminal — only PENDING requests need polling for the
                // consumer to resolve them to SUCCEEDED (or leave them stuck, which the test
                // below will catch via the await timeout).
                if (!"PENDING".equals(initialStatus)) {
                    return new PurchaseSubmission(token, requestId, initialStatus);
                }
                return new PurchaseSubmission(token, requestId, initialStatus);
            });
        }

        List<Future<PurchaseSubmission>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
        executor.shutdown();

        List<String> outcomes = new ArrayList<>();
        for (Future<PurchaseSubmission> future : futures) {
            PurchaseSubmission submission = future.get();
            if ("PENDING".equals(submission.status())) {
                outboxPublisher.publishPending();
                Message message = rabbitTemplate.receive(RabbitConfig.CREATE_ORDER_QUEUE, 5_000);
                assertThat(message).isNotNull();
                consumer.handle(message);
                outcomes.add(readStatus(submission.token(), submission.requestId()));
            } else {
                outcomes.add(submission.status());
            }
        }

        Map<String, Long> counts = outcomes.stream()
            .collect(Collectors.groupingBy(status -> status, Collectors.counting()));

        assertThat(counts.getOrDefault("SUCCEEDED", 0L))
            .as("exactly as many buyers as there was stock must succeed, no more")
            .isEqualTo((long) STOCK);
        assertThat(counts.getOrDefault("SOLD_OUT", 0L))
            .as("every other concurrent buyer must be told SOLD_OUT, not succeed and not error")
            .isEqualTo((long) (CONCURRENT_BUYERS - STOCK));

        Integer remainingStock = jdbcTemplate.queryForObject(
            "select available_quantity from inventory where flash_sale_id = 1", Integer.class);
        assertThat(remainingStock).as("inventory must never go negative or double-sell").isEqualTo(0);

        Integer orderCount = jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
        assertThat(orderCount).as("exactly one order must exist, matching the single unit of stock").isEqualTo(STOCK);
    }

    private String readStatus(String token, String requestId) throws Exception {
        MvcResult poll = mockMvc.perform(get("/api/purchase-requests/" + requestId)
                .header("Authorization", "Bearer " + token))
            .andReturn();
        return objectMapper.readTree(poll.getResponse().getContentAsString()).get("status").asText();
    }
}
