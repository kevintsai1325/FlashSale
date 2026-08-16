# Order Item Product Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show product name, quantity, and order-time unit price on both shopper order pages while preserving an immutable product-name snapshot.

**Architecture:** Add and backfill `order_items.product_name`, capture the catalog name when the asynchronous consumer creates an order, and centralize shopper DTO mapping. Expose `items[]` consistently from list/detail/mutation APIs and render that contract in both React pages.

**Tech Stack:** Java 21, Spring Boot 3, JPA, Flyway/PostgreSQL, JUnit 5, React 19, TypeScript, TanStack Query, Vitest/Testing Library.

## Global Constraints

- Historical orders display `order_items.product_name`, never the current catalog name.
- Backfill existing rows before applying `NOT NULL`.
- `OrderSummary` and `OrderDetail` expose `items: OrderItemView[]`.
- List, detail, cancel, and payment responses share one mapper.
- The backend `totalAmount` remains authoritative.
- Use red-green-refactor; run scoped tests per task and defer the complete integration suite until all current-round work is done.

---

### Task 1: Persist the product name snapshot

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__snapshot_product_name_on_order_items.sql`
- Modify: `backend/src/main/java/com/flashsale/order/domain/OrderItem.java`
- Modify: `backend/src/main/java/com/flashsale/order/domain/Order.java`
- Test: `backend/src/test/java/com/flashsale/order/domain/OrderTest.java`
- Test: `backend/src/test/java/com/flashsale/FlywayMigrationIT.java`

**Interfaces:**
- Consumes: `products(id, name)` and existing order items.
- Produces: `Order.createPendingPayment(Long, Long, String, int, BigDecimal)` and `OrderItem.getProductName()`.

- [ ] **Step 1: Write failing tests**

Create an order and assert the immutable item fields:

```java
Order order = Order.createPendingPayment(1L, 2L, "限量鍵盤", 3, new BigDecimal("499.00"));
OrderItem item = order.getItems().getFirst();
assertThat(item.getProductName()).isEqualTo("限量鍵盤");
assertThat(item.getQuantity()).isEqualTo(3);
assertThat(item.getUnitPrice()).isEqualByComparingTo("499.00");
```

Extend `FlywayMigrationIT` to query `information_schema.columns` and assert `product_name` exists with `is_nullable = 'NO'`.

- [ ] **Step 2: Verify RED**

From `backend`, run `.\gradlew.bat test --tests com.flashsale.order.domain.OrderTest --tests com.flashsale.FlywayMigrationIT`. Expected: compilation/schema failure because the field is absent.

- [ ] **Step 3: Implement migration and domain snapshot**

```sql
ALTER TABLE order_items ADD COLUMN product_name VARCHAR(255);
UPDATE order_items oi SET product_name = p.name FROM products p WHERE p.id = oi.product_id;
ALTER TABLE order_items ALTER COLUMN product_name SET NOT NULL;
```

Pass `productName` through `Order.createPendingPayment` and `OrderItem.of`, map it with `@Column(name = "product_name", nullable = false)`, and add the getter.

- [ ] **Step 4: Verify GREEN and commit**

Re-run the scoped tests, then commit:

```powershell
git add backend/src/main/resources/db/migration backend/src/main/java/com/flashsale/order/domain backend/src/test/java/com/flashsale/order/domain/OrderTest.java backend/src/test/java/com/flashsale/FlywayMigrationIT.java
git commit -m "feat: snapshot product names on order items"
```

### Task 2: Capture the catalog name during order creation

**Files:**
- Modify: `backend/src/main/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumer.java`
- Test: `backend/src/test/java/com/flashsale/order/adapter/messaging/OrderPurchaseConsumerIT.java`
- Modify: other consumer tests that instantiate the constructor.

**Interfaces:**
- Consumes: `ProductRepository.findById(Long)` and Task 1's factory.
- Produces: every newly persisted item contains the name present at message-consumption time.

- [ ] **Step 1: Write a failing consumer assertion**

After processing a product named `限量鍵盤`, query and assert:

```java
String snapshot = jdbcTemplate.queryForObject(
    "select product_name from order_items where order_id = ?", String.class, createdOrderId);
assertThat(snapshot).isEqualTo("限量鍵盤");
```

- [ ] **Step 2: Verify RED**

From `backend`, run `.\gradlew.bat test --tests com.flashsale.order.adapter.messaging.OrderPurchaseConsumerIT`. Expected: failure because the consumer does not resolve a name.

- [ ] **Step 3: Implement catalog lookup**

Inject `ProductRepository` and use:

```java
Product product = productRepository.findById(event.productId())
    .orElseThrow(() -> new IllegalStateException("Product " + event.productId() + " not found"));
Order order = Order.createPendingPayment(
    event.userId(), event.productId(), product.getName(), event.quantity(), event.unitPrice());
```

- [ ] **Step 4: Verify GREEN and commit**

Run all consumer-package tests, update constructor fixtures if necessary, and commit `feat: capture product names when creating orders`.

### Task 3: Return item snapshots from every shopper order API

**Files:**
- Create: `backend/src/main/java/com/flashsale/order/application/dto/OrderItemView.java`
- Create: `backend/src/main/java/com/flashsale/order/application/dto/OrderDtoMapper.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/dto/OrderSummary.java`
- Modify: `backend/src/main/java/com/flashsale/order/application/dto/OrderDetail.java`
- Modify: `backend/src/main/java/com/flashsale/order/adapter/web/OrderController.java`
- Modify: `backend/src/main/java/com/flashsale/payment/adapter/web/PaymentController.java`
- Test: focused order/payment mapper and controller tests.

**Interfaces:**
- Produces: `OrderItemView(Long productId, String productName, int quantity, BigDecimal unitPrice)`, `OrderDtoMapper.toSummary(Order)`, and `toDetail(Order)`.

- [ ] **Step 1: Write failing DTO/API assertions**

Assert mapper equality and list/detail/cancel/payment JSON paths:

```java
assertThat(result.items()).containsExactly(
    new OrderItemView(2L, "限量鍵盤", 3, new BigDecimal("499.00")));
```

```java
.andExpect(jsonPath("$.items[0].productName").value("限量鍵盤"))
.andExpect(jsonPath("$.items[0].quantity").value(3))
.andExpect(jsonPath("$.items[0].unitPrice").value(499.00));
```

Use `$[0].items[0]` for `/api/orders/me`.

- [ ] **Step 2: Verify RED**

Run only the affected mapper/controller tests. Expected: compilation or JSON-path failure because `items` is missing.

- [ ] **Step 3: Implement shared mapping**

Define both records with `List<OrderItemView> items`. Make `OrderDtoMapper` the only place that maps `OrderItem`, then replace inline constructors in `OrderController` and `PaymentController`.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused backend tests plus compilation and commit `feat: expose product snapshots in order responses`.

### Task 4: Render products on the list and detail pages

**Files:**
- Modify: `frontend/src/api/orderApi.ts`
- Modify/Test: `frontend/src/features/orders/MyOrdersPage.tsx`, `.css`, `.test.tsx`
- Modify/Test: `frontend/src/features/orders/OrderDetailPage.tsx`, `.css`, `.test.tsx`

**Interfaces:**
- Consumes: Task 3's `items[]`.
- Produces: product name and `數量 × $單價` for each item on both pages.

- [ ] **Step 1: Write failing component tests**

Add this mock item to list/detail responses:

```typescript
items: [{ productId: 2, productName: '限量鍵盤', quantity: 3, unitPrice: 499 }]
```

Assert on both pages:

```typescript
expect(await screen.findByText('限量鍵盤')).toBeInTheDocument()
expect(screen.getByText(/3\s*×\s*\$499\.00/)).toBeInTheDocument()
```

- [ ] **Step 2: Verify RED**

Run `npm test -- --run src/features/orders/MyOrdersPage.test.tsx src/features/orders/OrderDetailPage.test.tsx --maxWorkers=1`. Expected: missing item text.

- [ ] **Step 3: Implement types, markup, and scoped styling**

```typescript
export interface OrderItemView {
  productId: number
  productName: string
  quantity: number
  unitPrice: number
}
```

Add `items: OrderItemView[]` to `OrderSummary`, render semantic item lists, use `unitPrice.toFixed(2)`, and keep the current visual system. Preserve backend `totalAmount` without recalculation.

- [ ] **Step 4: Verify GREEN and commit**

Run both component tests, `npm run build`, and `npm run lint`; require zero lint errors (existing Fast Refresh warnings may remain). Commit `feat: show purchased products in my orders`.

### Task 5: Scoped regression verification

**Files:** Modify only files needed to fix a discovered regression.

**Interfaces:** Produces a clean tree with order/payment scoped tests and all frontend unit tests green.

- [ ] From `backend`, run order/payment scoped tests: `.\gradlew.bat test --tests "com.flashsale.order.*" --tests "com.flashsale.payment.*"`.
- [ ] Run all frontend unit tests: `npm test -- --run --maxWorkers=1`.
- [ ] Run `git diff --check` and `git status --short`; expect no changes.
- [ ] Do not run the full backend integration suite until all current-round work is complete.
