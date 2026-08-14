package com.flashsale.order.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end coverage of the synchronous, inventory-backed purchase flow: real HTTP requests
 * through Spring Security (real JWTs from register+login) and real idempotency-key persistence
 * in {@code purchase_requests}. No mocks for the sequential branches — this proves the happy
 * path, idempotent-replay, sold-out, and ownership/IDOR checks all work end to end against the
 * real schema.
 *
 * <p>The purchase-request flow is async: a successful request only reserves stock in Redis and
 * enqueues a {@code CreateOrderRequested} outbox event — it does not resolve to {@code SUCCEEDED}
 * or create an {@code Order} synchronously (that happens once Task 6's consumer processes the
 * event). This class therefore only asserts on the immediate {@code PENDING}/{@code SOLD_OUT}
 * admission outcome. It is not {@code @Transactional}: {@code InventoryStockGateway}/
 * {@code OutboxPublisher} are real beans talking to real Testcontainers Redis/RabbitMQ, and
 * {@code @Scheduled} methods like {@code OutboxPublisher} run on a separate thread outside any
 * test transaction anyway.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Sql("/db/testdata/inventory-fixtures.sql")
class PurchaseControllerIT {

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

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static org.testcontainers.containers.GenericContainer<?> redis =
        new org.testcontainers.containers.GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static org.testcontainers.containers.RabbitMQContainer rabbitmq =
        new org.testcontainers.containers.RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
        registry.add("JWT_PRIVATE_KEY", () -> TEST_PRIVATE_KEY);
        registry.add("JWT_PUBLIC_KEY", () -> TEST_PUBLIC_KEY);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", email);
                put("password", password);
            }});
    }

    private String registerAndLogin(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
                .content(requestBody(email, password)))
            .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(requestBody(email, password)))
            .andExpect(status().isOk())
            .andReturn();

        String responseBody = loginResult.getResponse().getContentAsString();
        return objectMapper.readTree(responseBody).get("accessToken").asText();
    }

    @Test
    void purchaseFlowCoversLockedInventoryIdempotentReplaySoldOutAndOwnershipCheck() throws Exception {
        String firstUserToken = registerAndLogin("ivy@example.com", "secret123");

        MvcResult firstPurchase = mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + firstUserToken)
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.orderId").isEmpty())
            .andExpect(jsonPath("$.requestId").isNotEmpty())
            .andReturn();

        String firstBody = firstPurchase.getResponse().getContentAsString();
        String firstRequestId = objectMapper.readTree(firstBody).get("requestId").asText();

        // Replay with the same idempotency key must return the identical requestId, not create
        // a second PurchaseRequest row or reserve stock twice.
        mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + firstUserToken)
                .header("Idempotency-Key", "key-1"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.requestId").value(firstRequestId));

        // A second user competing for the same (now-exhausted, stock=1) inventory must be
        // told SOLD_OUT rather than succeeding or erroring.
        String secondUserToken = registerAndLogin("jack@example.com", "secret123");
        mockMvc.perform(post("/api/flash-sales/1/purchase-requests")
                .header("Authorization", "Bearer " + secondUserToken)
                .header("Idempotency-Key", "key-2"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("SOLD_OUT"))
            .andExpect(jsonPath("$.orderId").isEmpty());

        mockMvc.perform(get("/api/purchase-requests/" + firstRequestId)
                .header("Authorization", "Bearer " + firstUserToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));

        // A different authenticated user must not be able to read another user's purchase
        // request by guessing/obtaining its requestId (IDOR check) — the API must respond as
        // if the resource simply doesn't exist for them, not leak its existence via a 403.
        mockMvc.perform(get("/api/purchase-requests/" + firstRequestId)
                .header("Authorization", "Bearer " + secondUserToken))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PURCHASE_REQUEST_NOT_FOUND"));
    }
}
