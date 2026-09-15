-- analytics-service 的讀取模型（P5）。
--
-- **這裡面沒有一筆是權威資料。** 每一列都是從 Kafka 的領域事件算出來的投影，
-- 隨時可以刪掉重建（把 consumer group 的 offset 倒回去重放即可）。
-- 這正是把事件放在 Kafka 而不是 RabbitMQ 的理由 —— 訊息被消費之後仍然在。
--
-- 為什麼用「每筆一列的投影表」而不是「一堆計數器」：
-- 計數器對重複與亂序毫無抵抗力（多加一次就永遠多一），而投影表的寫入是 upsert，
-- 天生冪等。發佈端是「至少一次」，這個選擇讓消費端不需要額外的去重表。

CREATE TABLE order_projection (
    order_id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    flash_sale_id BIGINT,
    product_id BIGINT,
    product_name VARCHAR(255),
    quantity INT NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_order_projection_created_at ON order_projection(created_at);
CREATE INDEX idx_order_projection_status ON order_projection(status);
CREATE INDEX idx_order_projection_flash_sale ON order_projection(flash_sale_id, created_at);

CREATE TABLE purchase_request_projection (
    request_id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL,
    flash_sale_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    order_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_purchase_request_projection_created_at ON purchase_request_projection(created_at);
CREATE INDEX idx_purchase_request_projection_status ON purchase_request_projection(status);
