package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminProductControllerIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String requestBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(new HashMap<>() {{
            put("email", email);
            put("password", password);
        }});
    }

    private String registerAndLogin(String email) throws Exception {
        String body = requestBody(email, "secret123");
        mockMvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private String registerAdminAndLogin(String email) throws Exception {
        String token = registerAndLogin(email);
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email);
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                .content(requestBody(email, "secret123")))
            .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    void adminCanCreateListAndUpdateAProduct() throws Exception {
        String adminToken = registerAdminAndLogin("product-admin@example.com");

        MvcResult createResult = mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Limited Sneakers\",\"description\":\"Only 100 pairs\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Limited Sneakers"))
            .andReturn();
        long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/admin/products").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")].name").value("Limited Sneakers"));

        mockMvc.perform(put("/api/admin/products/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Renamed Sneakers\",\"description\":\"Still only 100 pairs\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed Sneakers"));
    }

    @Test
    void blankNameIsRejectedWithValidationError() throws Exception {
        String adminToken = registerAdminAndLogin("product-admin-validation@example.com");

        mockMvc.perform(post("/api/admin/products")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"\",\"description\":\"x\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void nonAdminUserIsForbidden() throws Exception {
        String userToken = registerAndLogin("product-plain-user@example.com");

        mockMvc.perform(get("/api/admin/products").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
