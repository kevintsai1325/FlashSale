# 全服務即時健康度設計規格

- 涵蓋 Backend、PostgreSQL、Redis、RabbitMQ、Mailpit、Zipkin、Frontend、Nginx。
- 狀態限定 UP／DOWN／UNKNOWN；僅回傳固定中文原因，不洩漏 URL、body 或 exception。
- 核心四項由 Actuator contributors 取得；其餘固定內網 URL 以短 timeout 並行探測。
- 非核心服務降級不讓整體核心狀態 DOWN；不保存歷史、不提供任意 URL probe。
- `/api/admin/system-health` 沿用 ADMIN security；公開 health 仍只顯示 status。
- Nginx 僅 allowlist health、liveness、readiness、metrics；其他 actuator path 回 404。
- 後台每 30 秒更新並支援手動刷新，狀態同時用文字及可存取標籤呈現。
