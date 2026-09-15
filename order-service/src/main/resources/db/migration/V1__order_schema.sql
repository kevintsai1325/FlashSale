-- order-service 自己的 schema（P5）。
--
-- 為什麼 inventory 在這裡而不是留在 platform：
-- **不超賣的保證是「鎖住庫存列」與「建立訂單」在同一個本地交易裡**（見 OrderPurchaseConsumer）。
-- 把兩者拆到兩個資料庫，那個保證就得換成分散式交易或補償 —— 而補償無法防止超賣，
-- 只能在事後修正。這是整個 P5 唯一不能妥協的約束，其他邊界都是照著它決定的。
--
-- 代價：店面列表要顯示庫存數量，而那個資料現在在別的服務 —— platform 必須跨服務問。
-- 這是把「寫入的原子性」換到「讀取的複雜度」上，是刻意的交換。
--
-- 沒有任何外鍵指向別的服務的資料：user_id（platform 的 users）、flash_sale_id（platform 的
-- flash_sales）、product_id（platform 的 products）都只是數字。

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    flash_sale_id BIGINT,
    -- purchase-service 對外公開的 UUID，不是它的本地主鍵。
    purchase_request_id UUID,
    total_amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    payment_due_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_user_id ON orders(user_id);
CREATE INDEX idx_orders_flash_sale_id ON orders(flash_sale_id);

-- product_name 是下單當下的快照。商品改名之後，歷史訂單仍然顯示當時買的是什麼 ——
-- 這個欄位在拆分前就存在（V3），拆分後它額外承擔了「不必跨服務查商品」的職責。
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    product_id BIGINT NOT NULL,
    product_name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL
);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);

CREATE TABLE order_status_history (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    from_status VARCHAR(20),
    to_status VARCHAR(20) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_order_status_history_order_id ON order_status_history(order_id);

CREATE TABLE payment_records (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    result VARCHAR(20) NOT NULL,
    simulated_transaction_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_records_order_id ON payment_records(order_id);

-- 權威庫存。Redis 的計數器只是預扣用的快取，這張表才是真的。
CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    flash_sale_id BIGINT NOT NULL UNIQUE,
    total_quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    reserved_quantity INT NOT NULL DEFAULT 0,
    sold_quantity INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    partition_key VARCHAR(100),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events(published_at) WHERE published_at IS NULL;

CREATE TABLE consumed_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(255) NOT NULL UNIQUE,
    consumer_name VARCHAR(100) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
