package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminFlashSaleControllerIT extends AbstractIntegrationTest {

    /**
     * P5：更新活動時會先跨服務讀一次庫存（用來判斷總量有沒有被改）。
     * 共用底座預設回空 Map，那代表「查不到庫存」而不是「總量是 10」——
     * 這幾個測試需要一個具體的數字，所以在這裡補上。
     */
    private void stubExistingInventory(long flashSaleId, int totalQuantity) {
        org.mockito.Mockito.when(orderServiceClient.inventories(java.util.List.of(flashSaleId)))
            .thenReturn(java.util.Map.of(flashSaleId,
                new com.flashsale.common.client.OrderServiceClient.InventoryView(totalQuantity, totalQuantity, 0, 0)));
    }

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

    private long insertProduct(String name) {
        jdbcTemplate.update("insert into products (name) values (?)", name);
        return jdbcTemplate.queryForObject("select id from products where name = ?", Long.class, name);
    }

    @Test
    void adminCanCreateAndListAFlashSale() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin@example.com");
        long productId = insertProduct("Admin-Created Product");

        String body = "{\"productId\":" + productId + ",\"salePrice\":9.99," +
            "\"startsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(7200) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":50}";

        MvcResult createResult = mockMvc.perform(post("/api/admin/flash-sales")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn();
        long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(get("/api/admin/flash-sales").header("Authorization", "Bearer " + adminToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("SCHEDULED"));
    }

    @Test
    void createRejectsAStartAfterEndWithValidationError() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin-validation@example.com");
        long productId = insertProduct("Invalid Window Product");

        String body = "{\"productId\":" + productId + ",\"salePrice\":9.99," +
            "\"startsAt\":\"" + Instant.now().plusSeconds(7200) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":50}";

        mockMvc.perform(post("/api/admin/flash-sales")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateRejectsChangingPriceOnceTheSaleIsActive() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin-active@example.com");
        long productId = insertProduct("Active Sale Product");

        jdbcTemplate.update(
            "insert into flash_sales (product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (?, 9.99, now() - interval '1 hour', now() + interval '1 hour', 1, 'SCHEDULED')", productId);
        long saleId = jdbcTemplate.queryForObject(
            "select id from flash_sales where product_id = ?", Long.class, productId);
        stubExistingInventory(saleId, 10);

        String body = "{\"salePrice\":19.99," +
            "\"startsAt\":\"" + Instant.now().minusSeconds(3600) + "\"," +
            "\"endsAt\":\"" + Instant.now().plusSeconds(3600) + "\"," +
            "\"purchaseLimitPerUser\":1,\"totalQuantity\":10}";

        mockMvc.perform(put("/api/admin/flash-sales/" + saleId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isConflict());
    }

    @Test
    void adminCanFreelyUpdateAStillScheduledFlashSale() throws Exception {
        String adminToken = registerAdminAndLogin("flashsale-admin-scheduled@example.com");
        long productId = insertProduct("Scheduled Sale Product");

        jdbcTemplate.update(
            "insert into flash_sales (product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status) " +
            "values (?, 9.99, now() + interval '1 hour', now() + interval '2 hour', 1, 'SCHEDULED')", productId);
        long saleId = jdbcTemplate.queryForObject(
            "select id from flash_sales where product_id = ?", Long.class, productId);
        stubExistingInventory(saleId, 10);

        Instant newStarts = Instant.now().plusSeconds(3600 * 3);
        Instant newEnds = Instant.now().plusSeconds(3600 * 4);
        String body = "{\"salePrice\":29.99," +
            "\"startsAt\":\"" + newStarts + "\"," +
            "\"endsAt\":\"" + newEnds + "\"," +
            "\"purchaseLimitPerUser\":5,\"totalQuantity\":80}";

        mockMvc.perform(put("/api/admin/flash-sales/" + saleId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk());

        var saleRow = jdbcTemplate.queryForMap(
            "select sale_price, purchase_limit_per_user from flash_sales where id = ?", saleId);
        assertThat(((Number) saleRow.get("sale_price")).doubleValue()).isEqualTo(29.99);
        assertThat(((Number) saleRow.get("purchase_limit_per_user")).intValue()).isEqualTo(5);

        // P5：庫存在 order-service。platform 這邊能驗的是「有沒有把新的總量宣告過去」，
        // 而不是那一列長什麼樣 —— 後者由 order-service 自己的測試負責。
        org.mockito.Mockito.verify(orderServiceClient)
            .declareInventory(org.mockito.ArgumentMatchers.eq(saleId), org.mockito.ArgumentMatchers.eq(80));
    }

    @Test
    void nonAdminUserIsForbidden() throws Exception {
        String userToken = registerAndLogin("flashsale-plain-user@example.com");

        mockMvc.perform(get("/api/admin/flash-sales").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
