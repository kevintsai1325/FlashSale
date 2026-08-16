# Service Health Dashboard Implementation Plan

**Goal:** 為 8 個 Compose services 建立即時健康快照、ADMIN 聚合 API、後台頁面及最小 Actuator proxy。

## Tasks

- [x] Frontend／Nginx internal health endpoints 與 Compose healthchecks。
- [x] Actuator readiness/liveness groups。
- [x] 固定 URL HttpHealthProbe、sanitized reason 與 bounded parallel aggregation。
- [x] ADMIN-only `/api/admin/system-health`。
- [x] Nginx Actuator allowlist 與 config validation。
- [x] 後台健康頁、8 項狀態、30 秒 polling、手動刷新、nav／route。
- [x] Probe、aggregation 與 UI 單元測試。
- [ ] 最終完整 integration run、Compose healthy 與 curl matrix。
