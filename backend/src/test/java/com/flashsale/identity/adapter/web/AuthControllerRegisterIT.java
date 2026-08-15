package com.flashsale.identity.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class AuthControllerRegisterIT extends AbstractIntegrationTest {

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
