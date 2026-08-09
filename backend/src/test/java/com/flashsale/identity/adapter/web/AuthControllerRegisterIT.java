package com.flashsale.identity.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AuthControllerRegisterIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "2525");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void registerReturns201AndPersistsUser() throws Exception {
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", "bob@example.com");
                put("password", "secret123");
            }});

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("bob@example.com"));
    }

    @Test
    void duplicateEmailReturns409ProblemDetail() throws Exception {
        String body = objectMapper.writeValueAsString(
            new java.util.HashMap<>() {{
                put("email", "carol@example.com");
                put("password", "secret123");
            }});

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }
}
