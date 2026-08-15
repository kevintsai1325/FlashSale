# FlashSale Week 6(後台商品/搶購活動 CRUD)Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 補上後台商品/搶購活動的建立與編輯功能(從主規格「第二階段」提前到本輪),並修掉手動驗證
時發現的消費端視覺殘留(未套樣式的 `<h1>`、退回系統字型)。

**Architecture:** 後端沿用既有 `admin/adapter/web` + `admin/application` 的既有分層(比照
`AdminOrderController`/`AdminOrderQueryService`),不新增 module、不新增資料表——直接對既有
`Product`/`FlashSale`/`Inventory` 領域類別補上意圖導向的編輯方法(不裸露 setter)。前端新增兩個
後台頁面,沿用既有 admin 頁面的表格+表單風格(不是消費端的搶購票根視覺)。

**Tech Stack:** Spring Boot 3.3、Jakarta Bean Validation、JPA、React + react-hook-form + Zod +
TanStack Query(前端跟既有 admin 頁面一致,不用 `@tanstack/react-table`——這兩個新列表資料量小,
不需要排序/分頁複雜度,也避免這台機器上目前 `@tanstack/react-table` 沒裝好的既有環境問題)。

**Spec:** `docs/superpowers/specs/2026-08-15-flash-sale-week6-admin-crud-design.md`

## Global Constraints

- 商品/搶購活動都不做刪除(spec §1 明確排除)。
- 活動狀態維持純時間區間計算(`FlashSale.effectiveStatus`),不新增手動下架以外的狀態機
  (spec §1)。
- 已經開始的搶購活動(`effectiveStatus != SCHEDULED`)編輯時,只允許縮短 `endsAt`,其餘欄位一律
  拒絕、回 `409 FLASH_SALE_ALREADY_STARTED`(spec §4)。
- 欄位格式驗證(數值 > 0、時間區間 `startsAt < endsAt`)一律用 Bean Validation 註解 + 既有
  `GlobalExceptionHandler` 的 `MethodArgumentNotValidException` 處理,回 `400 VALIDATION_ERROR`
  (spec §4)。
- 兩個新 controller 都掛在 `/api/admin/**` 底下,沿用 `SecurityConfig` 既有的 `hasRole("ADMIN")`
  規則,不改 `SecurityConfig`(spec §3、§4)。
- 後台管理頁面用既有的中性後台樣式,不套用消費端的搶購票根視覺(spec §5)。
- Week 5 記錄的兩項技術債(共用 Testcontainers context、outbox trace context 傳遞)**不在本計畫
  範圍內**——spec §7 已經標記這兩項視時間狀況另外排入,共用 Testcontainers context 那項預期會動到
  29 個既有測試檔案,值得自己另開一份計畫,不跟這裡的任務混在一起。
- spec §6 提到的「其餘 7 個消費端畫面狀態比對」**在本計畫範圍內**,是 Task 6——但那是需要人眼逐一
  核對設計稿的探索性工作,沒辦法像 Task 1-5 一樣預先寫成固定步驟的 TDD 任務,Task 6 的產出是一份
  落差清單,不是預先寫死的程式碼改動(見 Task 6 說明)。

---

## Task 1: 商品(Product)後台 CRU API

**Files:**
- Modify: `backend/src/main/java/com/flashsale/catalog/domain/Product.java`
- Modify: `backend/src/main/java/com/flashsale/catalog/application/ProductRepository.java`
- Modify: `backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/admin/application/AdminProductService.java`
- Create: `backend/src/main/java/com/flashsale/admin/application/dto/ProductView.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/dto/ProductCreateRequest.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/dto/ProductUpdateRequest.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/AdminProductController.java`
- Test: `backend/src/test/java/com/flashsale/admin/application/AdminProductServiceTest.java`(new)
- Test: `backend/src/test/java/com/flashsale/admin/adapter/web/AdminProductControllerIT.java`(new)

**Interfaces:**
- Consumes:既有 `catalog.application.ProductRepository`(本任務幫它加上 `save()`)、既有
  `catalog.domain.Product`(本任務幫它加上 `rename()`)。
- Produces:`AdminProductService`(`create(String name, String description)`、
  `update(Long id, String name, String description)`、`listAll()` 三個 public 方法,回傳/接收
  `admin.application.dto.ProductView`),供 Task 4(搶購活動建立表單的商品下拉選單需要呼叫
  `GET /api/admin/products`)使用其 HTTP API。

- [ ] **Step 1: 寫失敗測試 `AdminProductServiceTest`**

新建檔案:

```java
package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.ProductView;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock ProductRepository productRepository;

    AdminProductService service;

    @Test
    void createSavesAndReturnsTheNewProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductView result = service.create("Limited Sneakers", "Only 100 pairs");

        assertThat(result.name()).isEqualTo("Limited Sneakers");
        assertThat(result.description()).isEqualTo("Only 100 pairs");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void updateRenamesAnExistingProduct() {
        service = new AdminProductService(productRepository);
        Product existing = Product.create("Old Name", "Old description");
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        ProductView result = service.update(1L, "New Name", "New description");

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.description()).isEqualTo("New description");
    }

    @Test
    void updateThrowsNotFoundForUnknownProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "x", "y")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listAllReturnsEveryProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.findAll()).thenReturn(List.of(
            Product.create("A", "a"), Product.create("B", "b")));

        List<ProductView> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("A");
        assertThat(result.get(1).name()).isEqualTo("B");
    }
}
```

- [ ] **Step 2: 執行測試,確認全部失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.application.AdminProductServiceTest"`
Expected: 編譯失敗——`AdminProductService`、`ProductView`、`ProductRepository.save()`/
`findAll()`、`Product.create(...)`(已存在)都還沒到位,或方法不存在。這一步的「FAIL」預期是編譯
錯誤,不是執行期斷言失敗,這是正常的——先把測試寫好、看著它因為型別/方法不存在而編譯失敗,再往下
一步一步把依賴補齊。

- [ ] **Step 3: `Product` 新增 `rename()`,`ProductRepository`/`ProductRepositoryImpl` 新增
`save()`/`findAll()`**

`backend/src/main/java/com/flashsale/catalog/domain/Product.java`,在 `getDescription()` 之後
加:

```java
    public void rename(String name, String description) {
        this.name = name;
        this.description = description;
    }
```

`backend/src/main/java/com/flashsale/catalog/application/ProductRepository.java` 整個檔案改成:

```java
package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(Long id);
    List<Product> findAll();
    Product save(Product product);
}
```

`backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductRepositoryImpl.java` 整個
檔案改成:

```java
package com.flashsale.catalog.adapter.persistence;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    public ProductRepositoryImpl(ProductJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Product> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Product> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public Product save(Product product) {
        return jpaRepository.save(product);
    }
}
```

（`ProductJpaRepository` 已經是 Spring Data `JpaRepository<Product, Long>`,`save()`/`findAll()`
本來就有,這裡只是把既有能力透過 port 介面暴露出來。）

- [ ] **Step 4: 新增 `ProductView`**

```java
package com.flashsale.admin.application.dto;

public record ProductView(Long id, String name, String description) {}
```

- [ ] **Step 5: 新增 `AdminProductService`**

```java
package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.ProductView;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminProductService {

    private final ProductRepository productRepository;

    public AdminProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductView create(String name, String description) {
        Product product = Product.create(name, description);
        Product saved = productRepository.save(product);
        return toView(saved);
    }

    @Transactional
    public ProductView update(Long id, String name, String description) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + id + " does not exist"));
        product.rename(name, description);
        return toView(product);
    }

    public List<ProductView> listAll() {
        return productRepository.findAll().stream().map(this::toView).toList();
    }

    private ProductView toView(Product product) {
        return new ProductView(product.getId(), product.getName(), product.getDescription());
    }
}
```

（`update()` 不用手動呼叫 `save()`——`product` 是這個 `@Transactional` 方法內從
`productRepository.findById()` 取得的受管理(managed)JPA entity,呼叫 `rename()` 修改欄位後,
交易提交時 Hibernate 的 dirty checking 會自動 flush,這跟整個專案既有的更新模式一致(參考
`CreatePurchaseRequestService`/`OrderCompensationService` 等既有程式碼,都沒有在更新既有 entity
時手動呼叫 `save()`)。）

- [ ] **Step 6: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.application.AdminProductServiceTest"`
Expected: 4 個測試全部 PASS。

- [ ] **Step 7: 新增 Controller DTO**

```java
package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductCreateRequest(@NotBlank String name, String description) {}
```

```java
package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProductUpdateRequest(@NotBlank String name, String description) {}
```

（兩個檔案分開放,雖然內容目前一樣——建立跟編輯的驗證規則之後很可能會分岔（例如編輯可能需要一個
"最後修改人" 欄位),現在就分開對應到不同語意,比事後再拆一次省事,參考既有
`LoginRequest`/`RegisterRequest` 分開放的既有慣例。）

- [ ] **Step 8: 新增 `AdminProductController`**

```java
package com.flashsale.admin.adapter.web;

import com.flashsale.admin.adapter.web.dto.ProductCreateRequest;
import com.flashsale.admin.adapter.web.dto.ProductUpdateRequest;
import com.flashsale.admin.application.AdminProductService;
import com.flashsale.admin.application.dto.ProductView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only product management behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule). No delete endpoint by design — see design spec §1.
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final AdminProductService adminProductService;

    public AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductView created = adminProductService.create(request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<ProductView> list() {
        return adminProductService.listAll();
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return adminProductService.update(id, request.name(), request.description());
    }
}
```

- [ ] **Step 9: 寫失敗測試 `AdminProductControllerIT`**

新建檔案,授權驗證與 Testcontainers 設定比照既有
`backend/src/test/java/com/flashsale/admin/adapter/web/AdminOrderControllerIT.java` 的既有寫法
(register+login、DB 直接把 role 改 ADMIN、重新登入拿到真正帶 ADMIN claim 的 JWT):

```java
package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminProductControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
```

- [ ] **Step 10: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminProductControllerIT"`
Expected: 3 個測試全部 PASS。

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/flashsale/catalog/domain/Product.java \
  backend/src/main/java/com/flashsale/catalog/application/ProductRepository.java \
  backend/src/main/java/com/flashsale/catalog/adapter/persistence/ProductRepositoryImpl.java \
  backend/src/main/java/com/flashsale/admin/application/AdminProductService.java \
  backend/src/main/java/com/flashsale/admin/application/dto/ProductView.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/dto/ProductCreateRequest.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/dto/ProductUpdateRequest.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/AdminProductController.java \
  backend/src/test/java/com/flashsale/admin/application/AdminProductServiceTest.java \
  backend/src/test/java/com/flashsale/admin/adapter/web/AdminProductControllerIT.java
git commit -m "feat: add admin product create/list/update API"
```

---

## Task 2: 搶購活動(FlashSale)後台 CRU API

**Files:**
- Modify: `backend/src/main/java/com/flashsale/flashsale/domain/FlashSale.java`
- Modify: `backend/src/main/java/com/flashsale/flashsale/application/FlashSaleRepository.java`
- Modify: `backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleRepositoryImpl.java`
- Create: `backend/src/main/java/com/flashsale/admin/application/AdminFlashSaleService.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/dto/FlashSaleCreateRequest.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/dto/FlashSaleUpdateRequest.java`
- Create: `backend/src/main/java/com/flashsale/admin/adapter/web/AdminFlashSaleController.java`
- Test: `backend/src/test/java/com/flashsale/admin/application/AdminFlashSaleServiceTest.java`(new)
- Test: `backend/src/test/java/com/flashsale/admin/adapter/web/AdminFlashSaleControllerIT.java`(new)

**Interfaces:**
- Consumes:Task 1 的 `ProductRepository`(確認 `productId` 存在);既有
  `FlashSaleRepository`(本任務加 `save()`)、既有 `InventoryRepository`(已經有 `save()`,見
  spec §4)、既有 `FlashSale.effectiveStatus(Instant)`(Week 5 已新增,直接用)。
- Produces:`GET /api/admin/flash-sales` 直接複用既有
  `flashsale.application.FlashSaleQueryService.listAll()`(不新增 admin 專用查詢),不產生新的
  read DTO;`POST`/`PUT` 使用本任務新增的 `FlashSaleCreateRequest`/`FlashSaleUpdateRequest`。

- [ ] **Step 1: 寫失敗測試 `AdminFlashSaleServiceTest`**

新建檔案:

```java
package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminFlashSaleServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;

    AdminFlashSaleService service;

    @Test
    void createWritesFlashSaleAndInventoryTogether() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.create("Sneakers", "desc")));
        when(flashSaleRepository.save(any(FlashSale.class))).thenAnswer(invocation -> {
            FlashSale sale = invocation.getArgument(0);
            return sale;
        });

        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = Instant.now().plusSeconds(7200);
        service.create(1L, new BigDecimal("9.99"), starts, ends, 1, 50);

        verify(flashSaleRepository).save(any(FlashSale.class));
        verify(inventoryRepository).save(any(Inventory.class));
    }

    @Test
    void createThrowsNotFoundForUnknownProduct() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1, 50))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateFreelyChangesEveryFieldWhileStillScheduled() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(Inventory.initialize(1L, 50)));

        Instant newStarts = Instant.now().plusSeconds(1800);
        Instant newEnds = Instant.now().plusSeconds(9000);
        service.update(1L, new BigDecimal("19.99"), newStarts, newEnds, 2, 80);

        assertThat(sale.getSalePrice()).isEqualByComparingTo("19.99");
        assertThat(sale.getStartsAt()).isEqualTo(newStarts);
        assertThat(sale.getEndsAt()).isEqualTo(newEnds);
        assertThat(sale.getPurchaseLimitPerUser()).isEqualTo(2);
    }

    @Test
    void updateRejectsPriceChangeOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant ends = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, ends, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("19.99"), starts, ends, 1, 50))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("started");
    }

    @Test
    void updateAllowsShorteningEndsAtOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));

        Instant earlierEnds = Instant.now().plusSeconds(60);
        service.update(1L, new BigDecimal("9.99"), starts, earlierEnds, 1, /* totalQuantity unused on this path */ 0);

        assertThat(sale.getEndsAt()).isEqualTo(earlierEnds);
    }

    @Test
    void updateRejectsExtendingEndsAtPastTheOriginalOnceStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));

        Instant laterEnds = originalEnds.plusSeconds(3600);

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("9.99"), starts, laterEnds, 1, 0))
            .isInstanceOf(ConflictException.class);
    }
}
```

- [ ] **Step 2: 執行測試,確認全部失敗**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.application.AdminFlashSaleServiceTest"`
Expected: 編譯失敗(`AdminFlashSaleService`、`FlashSaleRepository.save()`、
`FlashSale.reschedule()`/`endEarly()` 都還沒到位)。

- [ ] **Step 3: `FlashSale` 新增 `reschedule()`/`endEarly()`,`FlashSaleRepository`/
`FlashSaleRepositoryImpl` 新增 `save()`**

`backend/src/main/java/com/flashsale/flashsale/domain/FlashSale.java`,在 `effectiveStatus()`
方法之後加:

```java
    /**
     * Only valid while {@link #effectiveStatus} is {@code SCHEDULED} — the caller
     * ({@code AdminFlashSaleService}) is responsible for checking that before calling this;
     * this method itself has no side channel to verify "now" against {@code startsAt}.
     */
    public void reschedule(BigDecimal salePrice, Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {
        this.salePrice = salePrice;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.purchaseLimitPerUser = purchaseLimitPerUser;
    }

    /**
     * Shortens {@code endsAt} on a sale that has already started — the only edit allowed once
     * purchasing may be underway. The caller must have already verified
     * {@code !newEndsAt.isBefore(now) && !newEndsAt.isAfter(this.endsAt)}.
     */
    public void endEarly(Instant newEndsAt) {
        this.endsAt = newEndsAt;
    }
```

`backend/src/main/java/com/flashsale/flashsale/application/FlashSaleRepository.java` 整個檔案
改成:

```java
package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSale;
import java.util.List;
import java.util.Optional;

public interface FlashSaleRepository {
    List<FlashSale> findAll();
    Optional<FlashSale> findById(Long id);
    FlashSale save(FlashSale flashSale);
}
```

`backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleRepositoryImpl.java`
整個檔案改成:

```java
package com.flashsale.flashsale.adapter.persistence;

import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class FlashSaleRepositoryImpl implements FlashSaleRepository {

    private final FlashSaleJpaRepository jpaRepository;

    public FlashSaleRepositoryImpl(FlashSaleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<FlashSale> findAll() { return jpaRepository.findAll(); }

    @Override
    public Optional<FlashSale> findById(Long id) { return jpaRepository.findById(id); }

    @Override
    public FlashSale save(FlashSale flashSale) { return jpaRepository.save(flashSale); }
}
```

- [ ] **Step 4: 新增 `AdminFlashSaleService`**

```java
package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class AdminFlashSaleService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public AdminFlashSaleService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository,
                                  InventoryRepository inventoryRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public FlashSale create(Long productId, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        productRepository.findById(productId)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + productId + " does not exist"));

        FlashSale sale = FlashSale.schedule(productId, salePrice, startsAt, endsAt, purchaseLimitPerUser);
        FlashSale saved = flashSaleRepository.save(sale);
        inventoryRepository.save(Inventory.initialize(saved.getId(), totalQuantity));
        return saved;
    }

    @Transactional
    public FlashSale update(Long id, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + id + " does not exist"));
        Instant now = Instant.now();

        if (sale.effectiveStatus(now) == FlashSaleStatus.SCHEDULED) {
            sale.reschedule(salePrice, startsAt, endsAt, purchaseLimitPerUser);
            // A SCHEDULED sale has 0 reserved/sold quantity by construction (nothing can
            // purchase it yet), so it's safe to just re-initialize the whole inventory row to
            // the new totalQuantity rather than compute a partial adjustment.
            inventoryRepository.save(Inventory.initialize(id, totalQuantity));
            return sale;
        }

        boolean otherFieldsChanged = salePrice.compareTo(sale.getSalePrice()) != 0
            || !startsAt.equals(sale.getStartsAt())
            || purchaseLimitPerUser != sale.getPurchaseLimitPerUser();
        if (otherFieldsChanged) {
            throw new ConflictException("FLASH_SALE_ALREADY_STARTED",
                "Flash sale " + id + " has already started — only endsAt may be shortened");
        }
        if (endsAt.isBefore(now) || endsAt.isAfter(sale.getEndsAt())) {
            throw new ConflictException("FLASH_SALE_ALREADY_STARTED",
                "endsAt must be between now and the flash sale's current endsAt");
        }
        sale.endEarly(endsAt);
        return sale;
    }
}
```

（`update()` 的 SCHEDULED 分支用 `Inventory.initialize(id, totalQuantity)` 整個重建庫存列而不是
局部調整——`SCHEDULED` 階段依定義還沒有任何預扣/售出(`reservedQuantity`/`soldQuantity` 必為
0),直接重設 `totalQuantity`/`availableQuantity` 是安全的,不需要另外寫一個「調整庫存數量」的
`Inventory` 領域方法。已開始的分支完全不動庫存,呼叫端傳進來的 `totalQuantity` 直接忽略——這也是
為什麼 `AdminFlashSaleServiceTest` 裡「已開始」的兩個測試案例把 `totalQuantity` 參數傳
`0` 並註解說明用不到。）

- [ ] **Step 5: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.application.AdminFlashSaleServiceTest"`
Expected: 6 個測試全部 PASS。

- [ ] **Step 6: 新增 Controller DTO**

```java
package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleCreateRequest(
    @NotNull Long productId,
    @NotNull @Positive BigDecimal salePrice,
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    @Positive int purchaseLimitPerUser,
    @Positive int totalQuantity
) {
    @AssertTrue(message = "startsAt must be before endsAt")
    public boolean isValidTimeWindow() {
        return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
    }
}
```

```java
package com.flashsale.admin.adapter.web.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record FlashSaleUpdateRequest(
    @NotNull @Positive BigDecimal salePrice,
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    @Positive int purchaseLimitPerUser,
    @Positive int totalQuantity
) {
    @AssertTrue(message = "startsAt must be before endsAt")
    public boolean isValidTimeWindow() {
        return startsAt == null || endsAt == null || startsAt.isBefore(endsAt);
    }
}
```

（`@AssertTrue` 是標準 Jakarta Bean Validation 技巧,用來表達單一 DTO 內的跨欄位驗證——
`isValidTimeWindow()` 這個 boolean method 本身會被驗證框架當成一條規則跑,失敗時一樣會被既有
`GlobalExceptionHandler.handleValidation()` 捕捉、回 `400 VALIDATION_ERROR`,不需要另外寫
`ConstraintValidator` 類別。）

- [ ] **Step 7: 新增 `AdminFlashSaleController`**

```java
package com.flashsale.admin.adapter.web;

import com.flashsale.admin.adapter.web.dto.FlashSaleCreateRequest;
import com.flashsale.admin.adapter.web.dto.FlashSaleUpdateRequest;
import com.flashsale.admin.application.AdminFlashSaleService;
import com.flashsale.flashsale.application.FlashSaleQueryService;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only flash-sale management behind {@code /api/admin/**} (guarded by
 * {@code SecurityConfig}'s {@code hasRole("ADMIN")} rule). {@code GET} reuses the existing
 * {@link FlashSaleQueryService#listAll()} (design spec §4) rather than a new admin-specific read
 * path — {@link FlashSaleSummary} already has everything this list view needs.
 */
@RestController
@RequestMapping("/api/admin/flash-sales")
public class AdminFlashSaleController {

    private final AdminFlashSaleService adminFlashSaleService;
    private final FlashSaleQueryService flashSaleQueryService;

    public AdminFlashSaleController(AdminFlashSaleService adminFlashSaleService,
                                     FlashSaleQueryService flashSaleQueryService) {
        this.adminFlashSaleService = adminFlashSaleService;
        this.flashSaleQueryService = flashSaleQueryService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody FlashSaleCreateRequest request) {
        FlashSale created = adminFlashSaleService.create(request.productId(), request.salePrice(),
            request.startsAt(), request.endsAt(), request.purchaseLimitPerUser(), request.totalQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", created.getId()));
    }

    @GetMapping
    public List<FlashSaleSummary> list() {
        return flashSaleQueryService.listAll();
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody FlashSaleUpdateRequest request) {
        FlashSale updated = adminFlashSaleService.update(id, request.salePrice(), request.startsAt(),
            request.endsAt(), request.purchaseLimitPerUser(), request.totalQuantity());
        return Map.of("id", updated.getId(), "status", updated.effectiveStatus(Instant.now()).name());
    }
}
```

- [ ] **Step 8: 寫失敗測試 `AdminFlashSaleControllerIT`**

新建檔案,同樣比照 `AdminOrderControllerIT` 的授權驗證寫法:

```java
package com.flashsale.admin.adapter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.HashMap;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
class AdminFlashSaleControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

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
        jdbcTemplate.update("insert into inventory (flash_sale_id, total_quantity, available_quantity) values (?, 10, 10)", saleId);

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
    void nonAdminUserIsForbidden() throws Exception {
        String userToken = registerAndLogin("flashsale-plain-user@example.com");

        mockMvc.perform(get("/api/admin/flash-sales").header("Authorization", "Bearer " + userToken))
            .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 9: 執行測試,確認全部通過**

Run: `cd backend && ./gradlew test --tests "com.flashsale.admin.adapter.web.AdminFlashSaleControllerIT"`
Expected: 4 個測試全部 PASS。

- [ ] **Step 10: 跑一次全套 backend 測試,確認沒有連帶弄壞其他測試**

Run: `cd backend && ./gradlew test`
Expected: 全部 PASS(既有測試數量 + Task 1、Task 2 新增的測試)。這一步預期要 12-24 分鐘,是這個
計畫裡少數需要跑完整套件的時間點——後面的前端任務(Task 3、4)不會動到後端,不需要每個任務都重跑。

- [ ] **Step 11: Commit**

```bash
git add backend/src/main/java/com/flashsale/flashsale/domain/FlashSale.java \
  backend/src/main/java/com/flashsale/flashsale/application/FlashSaleRepository.java \
  backend/src/main/java/com/flashsale/flashsale/adapter/persistence/FlashSaleRepositoryImpl.java \
  backend/src/main/java/com/flashsale/admin/application/AdminFlashSaleService.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/dto/FlashSaleCreateRequest.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/dto/FlashSaleUpdateRequest.java \
  backend/src/main/java/com/flashsale/admin/adapter/web/AdminFlashSaleController.java \
  backend/src/test/java/com/flashsale/admin/application/AdminFlashSaleServiceTest.java \
  backend/src/test/java/com/flashsale/admin/adapter/web/AdminFlashSaleControllerIT.java
git commit -m "feat: add admin flash-sale create/list/update API"
```

---

## Task 3: 前端 — 商品管理頁面

**Files:**
- Modify: `frontend/src/api/adminApi.ts`
- Create: `frontend/src/features/admin/AdminProductsPage.tsx`
- Create: `frontend/src/features/admin/AdminProductsPage.css`
- Modify: `frontend/src/features/admin/AdminNav.tsx`
- Modify: `frontend/src/router.tsx`
- Test: `frontend/src/features/admin/AdminProductsPage.test.tsx`(new)

**Interfaces:**
- Consumes:Task 1 的 `POST/GET/PUT /api/admin/products`。
- Produces:`frontend/src/api/adminApi.ts` 新增 `ProductView` 型別與
  `listProducts()`/`createProduct()`/`updateProduct()` 三個函式,供 Task 4(搶購活動建立表單的
  商品下拉選單)呼叫 `listProducts()`。

- [ ] **Step 1: `adminApi.ts` 新增商品相關型別與函式**

在 `frontend/src/api/adminApi.ts` 檔案最後面加:

```typescript
/** Mirrors the backend's `admin.application.dto.ProductView` record. */
export interface ProductView {
  id: number
  name: string
  description: string | null
}

export async function listProducts(): Promise<ProductView[]> {
  const response = await apiFetch('/api/admin/products')
  return response.json()
}

export async function createProduct(name: string, description: string): Promise<ProductView> {
  const response = await apiFetch('/api/admin/products', {
    method: 'POST',
    body: JSON.stringify({ name, description }),
  })
  return response.json()
}

export async function updateProduct(id: number, name: string, description: string): Promise<ProductView> {
  const response = await apiFetch(`/api/admin/products/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ name, description }),
  })
  return response.json()
}
```

- [ ] **Step 2: 寫失敗測試 `AdminProductsPage.test.tsx`**

新建檔案:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminProductsPage } from './AdminProductsPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminProductsPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AdminProductsPage', () => {
  it('lists existing products', async () => {
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([
      { id: 1, name: 'Limited Sneakers', description: 'Only 100 pairs' },
    ])

    renderPage()

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
  })

  it('submits the create form and refreshes the list', async () => {
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([])
    const createSpy = vi.spyOn(adminApi, 'createProduct')
      .mockResolvedValue({ id: 2, name: 'New Product', description: 'desc' })

    renderPage()

    fireEvent.change(screen.getByLabelText('名稱'), { target: { value: 'New Product' } })
    fireEvent.change(screen.getByLabelText('說明'), { target: { value: 'desc' } })
    fireEvent.click(screen.getByRole('button', { name: '新增商品' }))

    await waitFor(() => expect(createSpy).toHaveBeenCalledWith('New Product', 'desc'))
  })
})
```

- [ ] **Step 3: 執行測試,確認失敗**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/admin/AdminProductsPage.test.tsx"`
Expected: FAIL——`AdminProductsPage` 這個 component 還不存在。

- [ ] **Step 4: 新增 `AdminProductsPage`**

```tsx
import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { listProducts, createProduct, type ProductView } from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminProductsPage.css'

export function AdminProductsPage() {
  const queryClient = useQueryClient()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['admin', 'products'],
    queryFn: listProducts,
  })

  const mutation = useMutation({
    mutationFn: () => createProduct(name, description),
    onSuccess: () => {
      setName('')
      setDescription('')
      queryClient.invalidateQueries({ queryKey: ['admin', 'products'] })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  return (
    <>
      <AdminNav />
      <div className="admin-products-page">
        <form className="admin-form" onSubmit={handleSubmit}>
          <label htmlFor="product-name">
            名稱
            <input id="product-name" value={name} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label htmlFor="product-description">
            說明
            <input id="product-description" value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <button type="submit" className="btn btn-primary">新增商品</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>

        {isLoading ? (
          <div>Loading…</div>
        ) : isError || !data ? (
          <div role="alert">Failed to load products.</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>名稱</th>
                <th>說明</th>
              </tr>
            </thead>
            <tbody>
              {data.length === 0 ? (
                <tr><td colSpan={2} className="admin-table-empty">尚無商品</td></tr>
              ) : (
                data.map((product: ProductView) => (
                  <tr key={product.id}>
                    <td>{product.name}</td>
                    <td>{product.description}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
```

（列表暫時不做行內編輯 UI——`updateProduct()` 這個 API client 函式先備妥給之後有需要時用,先讓
「建立 + 列表」這個最小可用流程動起來,符合 spec §5「YAGNI」的既有立場;如果之後要加編輯,再開一個
獨立任務。）

- [ ] **Step 5: 新增 `AdminProductsPage.css`**

```css
.admin-products-page {
  padding: 24px;
}

.admin-form {
  display: flex;
  gap: 16px;
  align-items: flex-end;
  margin-bottom: 24px;
}

.admin-form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.85rem;
  color: var(--muted);
}

.admin-form input {
  padding: 8px 10px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
}
```

（沿用既有 `--muted`/`--line-strong` design token,跟其他 admin 頁面一致的中性風格,不套用消費端
的搶購票根視覺——spec §5 已明講這個區分。）

- [ ] **Step 6: 執行測試,確認全部通過**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/admin/AdminProductsPage.test.tsx"`
Expected: 2 個測試全部 PASS。

- [ ] **Step 7: `AdminNav.tsx` 加連結、`router.tsx` 加路由**

`frontend/src/features/admin/AdminNav.tsx`,在 `訂單查詢` 的 `NavLink` 之後加:

```tsx
        <NavLink to="/admin/products" className={navLinkClassName}>
          商品管理
        </NavLink>
```

`frontend/src/router.tsx`,import 區塊加:

```tsx
import { AdminProductsPage } from './features/admin/AdminProductsPage'
```

`RequireAdmin` 底下的 children 陣列,在 `{ path: '/admin/orders', ... }` 之後加:

```tsx
      { path: '/admin/products', element: <AdminProductsPage /> },
```

- [ ] **Step 8: 跑一次全套 frontend 測試**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm test"`
Expected: 全部 PASS,`tsc -b` 也乾淨。**已知既有環境問題**:這台機器的 `frontend/node_modules`
沒有裝 `@tanstack/react-table`(`package.json`/`package-lock.json` 都有列,但 host 上的
`node_modules` 是舊的),會導致 `ApiLogsPage.tsx`/`AdminOrdersPage.tsx` 等既有用到這個套件的檔案
連帶讓它們的測試檔案跑不起來(`Failed to resolve import "@tanstack/react-table"`)——這不是本任務
造成的,本任務新增的 `AdminProductsPage` 沒有用這個套件。如果這個既有問題擋到驗證,執行前先跑
`docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine npm ci` 讓
`node_modules` 跟 lockfile 同步,不需要另外開任務處理。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/adminApi.ts \
  frontend/src/features/admin/AdminProductsPage.tsx \
  frontend/src/features/admin/AdminProductsPage.css \
  frontend/src/features/admin/AdminProductsPage.test.tsx \
  frontend/src/features/admin/AdminNav.tsx \
  frontend/src/router.tsx
git commit -m "feat: add admin products management page"
```

---

## Task 4: 前端 — 搶購活動管理頁面

**Files:**
- Modify: `frontend/src/api/adminApi.ts`
- Create: `frontend/src/features/admin/AdminFlashSalesPage.tsx`
- Create: `frontend/src/features/admin/AdminFlashSalesPage.css`
- Modify: `frontend/src/features/admin/AdminNav.tsx`
- Modify: `frontend/src/router.tsx`
- Test: `frontend/src/features/admin/AdminFlashSalesPage.test.tsx`(new)

**Interfaces:**
- Consumes:Task 2 的 `POST/GET /api/admin/flash-sales`;Task 3 的 `listProducts()`(建立表單的
  商品下拉選單)。
- Produces:無——這是本計畫消費端到後台管理鏈路的最後一個任務,沒有後續任務依賴它的輸出。

- [ ] **Step 1: `adminApi.ts` 新增搶購活動相關型別與函式**

在 `frontend/src/api/adminApi.ts` 檔案最後面(Task 3 加的內容之後)加:

```typescript
/** Mirrors the backend's `flashsale.application.dto.FlashSaleSummary` record. */
export interface AdminFlashSaleSummary {
  id: number
  productName: string
  salePrice: number
  startsAt: string
  endsAt: string
  status: string
}

export async function listAdminFlashSales(): Promise<AdminFlashSaleSummary[]> {
  const response = await apiFetch('/api/admin/flash-sales')
  return response.json()
}

export interface CreateFlashSaleInput {
  productId: number
  salePrice: number
  startsAt: string
  endsAt: string
  purchaseLimitPerUser: number
  totalQuantity: number
}

export async function createFlashSale(input: CreateFlashSaleInput): Promise<{ id: number }> {
  const response = await apiFetch('/api/admin/flash-sales', {
    method: 'POST',
    body: JSON.stringify(input),
  })
  return response.json()
}
```

- [ ] **Step 2: 寫失敗測試 `AdminFlashSalesPage.test.tsx`**

新建檔案:

```tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AdminFlashSalesPage } from './AdminFlashSalesPage'
import * as adminApi from '../../api/adminApi'

function renderPage() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AdminFlashSalesPage />
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('AdminFlashSalesPage', () => {
  it('lists existing flash sales and prompts to create a product when none exist', async () => {
    vi.spyOn(adminApi, 'listAdminFlashSales').mockResolvedValue([
      { id: 1, productName: 'Limited Sneakers', salePrice: 9.99, startsAt: '2026-08-15T10:00:00Z', endsAt: '2026-08-15T12:00:00Z', status: 'ENDED' },
    ])
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([])

    renderPage()

    await waitFor(() => expect(screen.getByText('Limited Sneakers')).toBeInTheDocument())
    expect(screen.getByText('請先建立商品')).toBeInTheDocument()
  })

  it('submits the create form when a product is available', async () => {
    vi.spyOn(adminApi, 'listAdminFlashSales').mockResolvedValue([])
    vi.spyOn(adminApi, 'listProducts').mockResolvedValue([
      { id: 5, name: 'Limited Sneakers', description: null },
    ])
    const createSpy = vi.spyOn(adminApi, 'createFlashSale').mockResolvedValue({ id: 10 })

    renderPage()

    await waitFor(() => expect(screen.getByLabelText('商品')).toBeInTheDocument())
    fireEvent.change(screen.getByLabelText('商品'), { target: { value: '5' } })
    fireEvent.change(screen.getByLabelText('售價'), { target: { value: '9.99' } })
    fireEvent.change(screen.getByLabelText('開始時間'), { target: { value: '2026-08-20T10:00' } })
    fireEvent.change(screen.getByLabelText('結束時間'), { target: { value: '2026-08-20T12:00' } })
    fireEvent.change(screen.getByLabelText('每人限購'), { target: { value: '1' } })
    fireEvent.change(screen.getByLabelText('庫存數量'), { target: { value: '50' } })
    fireEvent.click(screen.getByRole('button', { name: '新增搶購活動' }))

    await waitFor(() => expect(createSpy).toHaveBeenCalled())
    const input = createSpy.mock.calls[0][0]
    expect(input.productId).toBe(5)
    expect(input.salePrice).toBe(9.99)
    // Asserting the exact converted value would make this test depend on the test runner's
    // local timezone; asserting it round-trips through Date parsing to the same ISO string is
    // enough to confirm the conversion actually happened (a raw "2026-08-20T10:00" would fail
    // this, since new Date() on that exact string doesn't normally equal it after
    // .toISOString()).
    expect(new Date(input.startsAt).toISOString()).toBe(input.startsAt)
    expect(new Date(input.endsAt).toISOString()).toBe(input.endsAt)
    expect(input.purchaseLimitPerUser).toBe(1)
    expect(input.totalQuantity).toBe(50)
  })
})
```

- [ ] **Step 3: 執行測試,確認失敗**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/admin/AdminFlashSalesPage.test.tsx"`
Expected: FAIL——`AdminFlashSalesPage` 還不存在。

- [ ] **Step 4: 新增 `AdminFlashSalesPage`**

```tsx
import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  listAdminFlashSales,
  listProducts,
  createFlashSale,
  type AdminFlashSaleSummary,
} from '../../api/adminApi'
import { AdminNav } from './AdminNav'
import './AdminFlashSalesPage.css'

export function AdminFlashSalesPage() {
  const queryClient = useQueryClient()
  const [productId, setProductId] = useState('')
  const [salePrice, setSalePrice] = useState('')
  const [startsAt, setStartsAt] = useState('')
  const [endsAt, setEndsAt] = useState('')
  const [purchaseLimitPerUser, setPurchaseLimitPerUser] = useState('1')
  const [totalQuantity, setTotalQuantity] = useState('')

  const salesQuery = useQuery({
    queryKey: ['admin', 'flash-sales'],
    queryFn: listAdminFlashSales,
  })

  const productsQuery = useQuery({
    queryKey: ['admin', 'products'],
    queryFn: listProducts,
  })

  const mutation = useMutation({
    mutationFn: () => createFlashSale({
      productId: Number(productId),
      salePrice: Number(salePrice),
      // The <input type="datetime-local"> value has no timezone info and is interpreted as
      // local wall-clock time — new Date(...) parses it the same way, so .toISOString()
      // correctly converts it to the UTC instant string the backend's `Instant` expects.
      startsAt: new Date(startsAt).toISOString(),
      endsAt: new Date(endsAt).toISOString(),
      purchaseLimitPerUser: Number(purchaseLimitPerUser),
      totalQuantity: Number(totalQuantity),
    }),
    onSuccess: () => {
      setSalePrice('')
      setStartsAt('')
      setEndsAt('')
      setTotalQuantity('')
      queryClient.invalidateQueries({ queryKey: ['admin', 'flash-sales'] })
    },
  })

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate()
  }

  const products = productsQuery.data ?? []

  return (
    <>
      <AdminNav />
      <div className="admin-flash-sales-page">
        {products.length === 0 && !productsQuery.isLoading && (
          <p role="alert">請先建立商品</p>
        )}

        <form className="admin-form" onSubmit={handleSubmit}>
          <label htmlFor="flash-sale-product">
            商品
            <select id="flash-sale-product" value={productId} onChange={(e) => setProductId(e.target.value)} required>
              <option value="" disabled>請選擇商品</option>
              {products.map((product) => (
                <option key={product.id} value={product.id}>{product.name}</option>
              ))}
            </select>
          </label>
          <label htmlFor="flash-sale-price">
            售價
            <input id="flash-sale-price" type="number" step="0.01" value={salePrice} onChange={(e) => setSalePrice(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-starts">
            開始時間
            <input id="flash-sale-starts" type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-ends">
            結束時間
            <input id="flash-sale-ends" type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-limit">
            每人限購
            <input id="flash-sale-limit" type="number" min="1" value={purchaseLimitPerUser} onChange={(e) => setPurchaseLimitPerUser(e.target.value)} required />
          </label>
          <label htmlFor="flash-sale-quantity">
            庫存數量
            <input id="flash-sale-quantity" type="number" min="1" value={totalQuantity} onChange={(e) => setTotalQuantity(e.target.value)} required />
          </label>
          <button type="submit" className="btn btn-primary" disabled={products.length === 0}>新增搶購活動</button>
          {mutation.isError && <p role="alert">{(mutation.error as Error).message}</p>}
        </form>

        {salesQuery.isLoading ? (
          <div>Loading…</div>
        ) : salesQuery.isError || !salesQuery.data ? (
          <div role="alert">Failed to load flash sales.</div>
        ) : (
          <table className="admin-table">
            <thead>
              <tr>
                <th>商品</th>
                <th>售價</th>
                <th>開始</th>
                <th>結束</th>
                <th>狀態</th>
              </tr>
            </thead>
            <tbody>
              {salesQuery.data.length === 0 ? (
                <tr><td colSpan={5} className="admin-table-empty">尚無搶購活動</td></tr>
              ) : (
                salesQuery.data.map((sale: AdminFlashSaleSummary) => (
                  <tr key={sale.id}>
                    <td>{sale.productName}</td>
                    <td>${sale.salePrice.toFixed(2)}</td>
                    <td>{new Date(sale.startsAt).toLocaleString()}</td>
                    <td>{new Date(sale.endsAt).toLocaleString()}</td>
                    <td>{sale.status}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        )}
      </div>
    </>
  )
}
```

（`<input type="datetime-local">` 給的值(例如 `2026-08-20T10:00`)沒有時區資訊,瀏覽器把它當
本地時間解讀;`new Date(startsAt)` 用同一套解讀方式建構,`.toISOString()` 再轉成後端 `Instant`
反序列化需要的帶時區 UTC 字串——如果不做這個轉換,後端會因為收到不合法的 ISO-8601 格式直接在 JSON
反序列化階段丟例外,不會進到 `MethodArgumentNotValidException`/既有 `GlobalExceptionHandler` 那條
乾淨的 400 路徑,使用者只會看到一個沒有清楚訊息的錯誤——這不是可以接受的「之後再處理」,必須在這個
任務內做對。列表同樣暫時不做行內編輯 UI,理由跟 Task 3 的 `AdminProductsPage` 一致——先讓「建立 +
列表」這個最小可用流程動起來,`PUT /api/admin/flash-sales/{id}`(Task 2 已經做好)先備妥給之後有
需要時用。)

- [ ] **Step 5: 新增 `AdminFlashSalesPage.css`**

```css
.admin-flash-sales-page {
  padding: 24px;
}

.admin-flash-sales-page .admin-form {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  align-items: flex-end;
  margin-bottom: 24px;
}

.admin-flash-sales-page .admin-form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.85rem;
  color: var(--muted);
}

.admin-flash-sales-page .admin-form input,
.admin-flash-sales-page .admin-form select {
  padding: 8px 10px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
}
```

- [ ] **Step 6: 執行測試,確認全部通過**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/features/admin/AdminFlashSalesPage.test.tsx"`
Expected: 2 個測試全部 PASS。

- [ ] **Step 7: `AdminNav.tsx` 加連結、`router.tsx` 加路由**

`frontend/src/features/admin/AdminNav.tsx`,在 Task 3 加的「商品管理」連結之後加:

```tsx
        <NavLink to="/admin/flash-sales" className={navLinkClassName}>
          搶購活動管理
        </NavLink>
```

`frontend/src/router.tsx`,import 區塊加:

```tsx
import { AdminFlashSalesPage } from './features/admin/AdminFlashSalesPage'
```

`RequireAdmin` 底下的 children 陣列,在 Task 3 加的 `/admin/products` 之後加:

```tsx
      { path: '/admin/flash-sales', element: <AdminFlashSalesPage /> },
```

- [ ] **Step 8: 跑一次全套 frontend 測試**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm test"`
Expected: 全部 PASS(見 Task 3 Step 8 已經記錄的 `@tanstack/react-table` 既有環境問題,同樣的處理
方式)。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/api/adminApi.ts \
  frontend/src/features/admin/AdminFlashSalesPage.tsx \
  frontend/src/features/admin/AdminFlashSalesPage.css \
  frontend/src/features/admin/AdminFlashSalesPage.test.tsx \
  frontend/src/features/admin/AdminNav.tsx \
  frontend/src/router.tsx
git commit -m "feat: add admin flash sales management page"
```

---

## Task 5: 消費端視覺補齊 — 移除殘留標題、補上客製字體

**Files:**
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/App.test.tsx`
- Modify: `frontend/src/styles/tokens.css`
- (已完成,無需動作)`frontend/public/fonts/archivo-black-sub.woff2` —— 這個檔案已經在本次
  規劃對話中,從設計提案 artifact 的 base64 內嵌字型解碼落地,不需要在這個任務裡重新處理。

**Interfaces:**
- Consumes:無。
- Produces:無——消費端頁面視覺呈現的改動,沒有其他任務依賴它的介面。

- [ ] **Step 1: 確認字型檔案已經存在**

Run(不是寫程式碼,只是確認前提條件成立):
`ls -la frontend/public/fonts/archivo-black-sub.woff2`
Expected:檔案存在,大小約 9KB。如果檔案不存在,STOP 並回報給 controller——這個檔案是先前已經
從設計提案 artifact 解碼好放進去的,不在這個任務的步驟範圍內重新產生。

- [ ] **Step 2: 修正 `App.test.tsx`(先改測試,對應接下來要刪除的 `<h1>`)**

`frontend/src/App.test.tsx` 整個檔案改成:

```tsx
import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import App from './App'
import * as authApi from './api/authApi'

describe('App', () => {
  it('calls refresh on mount to restore the session after a page reload', async () => {
    const refreshSpy = vi.spyOn(authApi, 'refresh').mockRejectedValue(new Error('no session'))
    render(<App />)
    await waitFor(() => expect(refreshSpy).toHaveBeenCalled())
  })
})
```

（移除了 `renders the FlashSale heading` 這個案例——它斷言的 `<h1>FlashSale</h1>` 本身就是這個
任務要刪除的殘留元素,`App` 層級不會再有任何標題;品牌呈現改成完全由每一頁自己的 `AppNav`/
`AdminNav` 負責,那些元件已經有自己的測試覆蓋,不需要在 `App.test.tsx` 這一層重複斷言。）

- [ ] **Step 3: 執行測試,確認通過(這一步驗證的是「刪除案例後測試檔案本身沒壞」,不是 TDD 的
RED)**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/App.test.tsx"`
Expected: 1 個測試 PASS(這一步 `App.tsx` 本體還沒改,`<h1>` 還在畫面上,只是已經沒有測試在斷言
它,所以應該要過)。

- [ ] **Step 4: 刪除 `App.tsx` 的殘留 `<h1>`**

`frontend/src/App.tsx`,把:

```tsx
  return (
    <>
      <h1>FlashSale</h1>
      <RouterProvider router={router} />
    </>
  )
```

改成:

```tsx
  return <RouterProvider router={router} />
```

- [ ] **Step 5: 執行測試,確認通過**

Run:
`MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npx vitest run src/App.test.tsx"`
Expected: 1 個測試 PASS。

- [ ] **Step 6: `tokens.css` 的 `@font-face` 改指向真正的字型檔**

`frontend/src/styles/tokens.css`,把:

```css
  /* ponytail: skipped webfont embedding, --font-display falls back to system Arial Black — add the real subsetted woff2 (already produced once during the mockup, ask the controller session if it still has it) if the fallback reads too plain once it's live. */
  --font-display: "Archivo Black Sub", "Arial Black", sans-serif;
```

改成:

```css
  --font-display: "Archivo Black Sub", "Arial Black", sans-serif;
```

（拿掉那行過時的註解——字型現在已經是真的了,不再是待補的 fallback。）在 `:root { ... }` 區塊
**之前**(檔案最開頭)加上這個 `@font-face` 宣告:

```css
@font-face {
  font-family: "Archivo Black Sub";
  src: url("/fonts/archivo-black-sub.woff2") format("woff2");
  font-weight: 900;
  font-style: normal;
  font-display: swap;
}

```

（`/fonts/archivo-black-sub.woff2` 這個路徑對應 Vite 的 `public/` 目錄慣例——`frontend/public/`
底下的檔案在建置後會原樣複製到輸出根目錄,瀏覽器端直接用絕對路徑 `/fonts/...` 存取,不需要透過
`import` 或額外設定。）

- [ ] **Step 7: 目視驗證字型有生效(不是自動化測試——字型渲染的正確性用人眼核對,比照 Week 5
spec §10 對「視覺呈現」的既有立場)**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -p 5173:5173 -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm run dev -- --host"`,
瀏覽器開 `http://localhost:5173/register`,確認「建立帳號」等使用 `--font-display` 的標題文字
呈現的是 Archivo Black 的粗黑體風格,不是先前的系統 Arial Black fallback(兩者實際上可能很像,
若不確定可以打開瀏覽器開發者工具的 Network 分頁確認 `archivo-black-sub.woff2` 有被成功下載,
狀態碼 200)。跑完按 Ctrl+C 停掉 dev server。

- [ ] **Step 8: 跑一次全套 frontend 測試**

Run: `MSYS_NO_PATHCONV=1 docker run --rm -v "C:/SideProject/FlashSale/frontend:/app" -w /app node:20-alpine sh -c "npm test"`
Expected: 全部 PASS(同 Task 3 Step 8 記錄的既有 `@tanstack/react-table` 環境問題)。

- [ ] **Step 9: Commit**

```bash
git add frontend/src/App.tsx frontend/src/App.test.tsx frontend/src/styles/tokens.css \
  frontend/public/fonts/archivo-black-sub.woff2
git commit -m "fix: remove orphaned unstyled App heading, wire up the real display webfont"
```

---

## Task 6: 消費端其餘畫面狀態與設計稿核對

**Files:** 無預先指定——這個任務的產出是一份落差清單,不是預先寫死的程式碼改動。實際要不要修、
修哪些檔案,由這份清單的內容決定,超出清單範圍的修改不屬於這個任務(YAGNI——不要看到落差就
自動連帶開始重構,先列出來讓使用者確認要修哪些)。

**Interfaces:**
- Consumes:Task 1-4 建立的真實商品/搶購活動資料(需要有真的資料可以操作搶購流程、輪詢結果、
  訂單頁面,才能看到全部 10 個畫面狀態)。
- Produces:一份落差清單(格式見 Step 3),交給 controller 跟使用者討論後續是否要修、修多少。

- [ ] **Step 1: 重新取得設計稿**

設計稿原始檔是 claude.ai 上的一份 artifact(標題「搶購票根」),不在版控裡。執行這個任務時,請
controller 跟使用者要一次目前的 artifact URL(本計畫撰寫當下的 session 已經 fetch 過一次,但
URL 沒有記錄在版控檔案裡,需要使用者重新提供或從對話紀錄找)。用 `WebFetch` 工具讀取,取得完整
10 個畫面狀態的視覺設計(瀏覽與搶購 3 個、帳號 2 個、輪詢結果 3 個、我的訂單 2 個——實際分類以
artifact 內容為準,`docs/superpowers/specs/2026-08-15-flash-sale-week6-admin-crud-design.md` §2.4
已經核對過其中 3 個,這裡處理剩下的)。

- [ ] **Step 2: 實際操作應用程式,截圖每個畫面狀態**

啟動最新 build(`docker compose up --build -d`),用 Task 1-4 新建立的管理頁面建立至少一個
「即將開賣」跟一個「搶購中」的活動(涵蓋輪詢中/成功/失敗三種購買結果,需要真的走一次搶購流程),
用瀏覽器或既有的 Playwright/chromium-cli 驅動方式(見本次規劃對話中已經示範過的手法,沒有
`chromium-cli` 的話用 `mcr.microsoft.com/playwright` docker image)逐一截圖:活動詳情頁、
購買中(輪詢)頁、購買成功頁、購買失敗/售完頁、我的訂單列表、訂單詳情、空狀態(尚無訂單時的
列表頁)。

- [ ] **Step 3: 逐一比對,寫落差清單**

針對每個畫面狀態,比對截圖跟設計稿,記錄:
- 一致的部分(不用列出來,只列有落差的)。
- 有落差的部分:具體是什麼(顏色、間距、缺少的元素、文案),以及被判斷成「明顯殘留/缺漏」
  (比照 §2.4 已修的兩項的判斷標準——例如完全沒套用設計系統樣式的元素、或程式碼裡有註解明講
  是權宜/待補的部分)還是「設計提案本來就是草案方向,不代表現有實作一定要照抄」。

把這份清單交給 controller,由 controller 整理後跟使用者討論——這一步不自動決定要修哪些,是本
任務唯一的產出。

---

## 執行順序與收尾

Task 1 → 2 → 3 → 4 → 5 → 6(Task 3/4 依賴 Task 1/2 的 API 存在;Task 5 跟前四個任務沒有依賴
關係,可以視執行方式調整順序,但建議留在 Task 6 之前,因為 Task 5 的目視驗證跟 Task 6 都需要
實際登入後操作頁面,可以一起看;Task 6 一定要放最後,因為它需要 Task 1-4 建立的真實資料才能看到
完整的 10 個畫面狀態)。

全部任務完成、個別 review 都過了以後,依照 `superpowers:finishing-a-development-branch` 的既有
流程(跟 Week 2-5 一樣)做一次 whole-branch final review,通過後 merge/push——push 前跟既有規矩
一樣需要先跟使用者確認。

**本計畫刻意不包含**(spec §7 已經說明理由,這裡重申避免執行時誤解成遺漏):Week 5 記錄的共用
Testcontainers context 技術債、outbox trace context 傳遞技術債。這兩項等 Task 1-6 完成後,由
controller 跟使用者討論排入後續計畫。
