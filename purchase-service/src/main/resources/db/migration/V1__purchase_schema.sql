-- purchase-service 自己的 schema。P4 步驟 2：它不再與 backend 共用資料庫。
--
-- 兩張表、沒有任何外鍵指向別的服務的資料：
--   * user_id 指向 platform 的 users，flash_sale_id 指向 platform 的 flash_sales，
--     order_id 指向 order-service 的 orders —— 全部只是數字，不是外鍵。
--     跨服務的參照完整性由流程保證（事件、補償），不是由資料庫保證。
--     這是 database per service 最直接的代價，要清楚寫出來而不是假裝沒有。
--   * 拆分前這些欄位都有外鍵。少了它們之後，「指向一筆不存在的活動」在資料庫層面
--     變成可能 —— 擋這件事的是 CreatePurchaseRequestService 先去問 backend 活動存不存在。

CREATE TABLE purchase_requests (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL,
    flash_sale_id BIGINT NOT NULL,
    order_id BIGINT,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, flash_sale_id, idempotency_key)
);
CREATE INDEX idx_purchase_requests_user_flash_sale ON purchase_requests(user_id, flash_sale_id);
-- 儀表板的統計端點會掃「最近 24 小時」，沒有這個索引它是全表掃描。
CREATE INDEX idx_purchase_requests_created_at ON purchase_requests(created_at);

-- 自己的 outbox。步驟 1 兩個服務撈同一張表（靠 SKIP LOCKED 互不搶單），
-- 現在各有一張、互不相干 —— 而 outbox 的意義本來就是「與本地交易同生共死」，
-- 共用一張表的那個版本其實違背了這一點：purchase-service 的交易提交時，
-- 寫進去的是別人資料庫裡的列。
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    trace_context JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_events_unpublished ON outbox_events(published_at) WHERE published_at IS NULL;
