package com.flashsale.identity.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Reproduces findings #2 and #3 from the Week 1 review: Spring Security's own 401/403
 * responses (thrown by the filter chain, before any controller/GlobalExceptionHandler runs)
 * used to bypass the app's ProblemDetail contract entirely (empty body, no code/traceId).
 */
class SecurityErrorResponseIT extends AbstractIntegrationTest {

    // Test-only RSA key pair, generated once via:
    //   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048
    //   openssl rsa -pubout
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
    @Autowired ObjectMapper objectMapper;

    @Test
    void unauthenticatedRequestToProtectedEndpointReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/orders/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.traceId").exists())
            .andExpect(jsonPath("$.instance").value("/api/orders/me"));
    }

    @Test
    void authenticatedButInsufficientRoleReturnsProblemDetail() throws Exception {
        String email = "grace@example.com";
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", email);
                put("password", "secret123");
            }});

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        String loginResponse = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String accessToken = objectMapper.readTree(loginResponse).get("accessToken").asText();

        mockMvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.valueOf("application/problem+json")))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
            .andExpect(jsonPath("$.traceId").exists());
    }
}
