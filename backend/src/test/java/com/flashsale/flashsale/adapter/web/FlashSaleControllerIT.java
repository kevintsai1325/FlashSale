package com.flashsale.flashsale.adapter.web;

import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the public, unauthenticated read APIs for flash sales: {@code GET /api/flash-sales}
 * and {@code GET /api/flash-sales/{id}}. Deliberately never sets an Authorization header on any
 * request in this class — that absence is the point, since these endpoints must be reachable by
 * anonymous shoppers per spec (product browsing needs no auth).
 */
@Sql("/db/testdata/flashsale-fixtures.sql")
@Transactional
class FlashSaleControllerIT extends AbstractIntegrationTest {

    // Test-only RSA key pair (same as AuthControllerLoginIT), needed only because JwtKeyConfig
    // requires JWT_PRIVATE_KEY/JWT_PUBLIC_KEY to build the app context — these endpoints
    // themselves are exercised with no Authorization header at all.
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

    @DynamicPropertySource
    static void jwtKeyProps(DynamicPropertyRegistry registry) {
        registry.add("JWT_PRIVATE_KEY", () -> TEST_PRIVATE_KEY);
        registry.add("JWT_PUBLIC_KEY", () -> TEST_PUBLIC_KEY);
    }

    @Autowired MockMvc mockMvc;

    @Test
    void listReturnsSeededSummaryWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/flash-sales"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].productName").value("Limited Sneakers"))
            .andExpect(jsonPath("$[0].salePrice").value(9.99))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void detailReturnsMatchingFieldsWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/flash-sales/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.productName").value("Limited Sneakers"))
            .andExpect(jsonPath("$.productDescription").value("Only 100 pairs"))
            .andExpect(jsonPath("$.salePrice").value(9.99))
            .andExpect(jsonPath("$.purchaseLimitPerUser").value(1))
            .andExpect(jsonPath("$.totalQuantity").value(100))
            .andExpect(jsonPath("$.availableQuantity").value(42))
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void detailReturns404ForUnknownSaleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/flash-sales/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("FLASH_SALE_NOT_FOUND"));
    }
}
