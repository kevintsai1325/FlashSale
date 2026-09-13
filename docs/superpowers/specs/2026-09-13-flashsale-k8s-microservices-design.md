# FlashSale K8s 微服務化與即時數據平台設計規格

- 以現有 FlashSale 為基礎擴充，不另開專案；Kubernetes 取代 Docker Compose 成為主環境，Compose 保留為開發與 CI 用。
- 單體拆成 3 個業務微服務（purchase／order／platform）加 1 個 analytics-service，切分依據是擴縮需求而非 bounded context 數量。
- 每個服務一個獨立 Postgres database，禁止跨庫查詢；一致性靠 Transactional Outbox 與事件最終一致。
- 分散式鎖用 Redisson 保護 4 個排程任務，另以 Kubernetes Lease API 實作一份 leader election 做故障模式對照。
- 事件流用 Kafka，串流運算用 Flink on K8s，產出即時 GMV 大屏與後台儀表板的 CQRS 讀取模型。
- 分六個階段推進（P1–P6），每階段結束都是可運作、可壓測、可展示的系統，並留下 before／after 對照數據。
- 不做 service mesh、不做 GitOps、不做離線批次（Spark／資料湖），不改動既有業務規則。

## 目標

練習三個主題，並為每個主題留下可重現的量測證據：

1. **負載平衡**：Kubernetes Service 層的流量分配、水平擴展的實際收益與極限、自動擴縮在秒殺場景的適用性。
2. **分散式鎖**：多副本環境下排程任務的互斥執行，含鎖續期、鎖失效、持鎖節點故障與 fencing token。
3. **大數據處理**：Kafka 事件流的分區與順序保證，Flink 串流運算的視窗、亂序處理、狀態與 exactly-once。

次要目標：把既有作品集從「單體加 Compose」升級為「微服務加 K8s」，並誠實記錄拆分帶來的代價。

## 非目標

- 不導入 service mesh（Istio／Linkerd）。它的流量管理會模糊掉應用層高併發這個主題。
- 不導入 GitOps（ArgoCD／Flux）。
- 不做離線批次分析、資料湖、Spark。大數據這一層只做即時串流。
- 不改動既有業務規則、API 契約與前端流程。拆分是結構重整，不是功能開發。
- 不追求生產級高可用。Postgres／Redis／Kafka 皆為單副本，故障即停擺，這是本機資源下的刻意取捨。

## 現況盤點（2026-09-13）

### 已完成

- `k8s/base` 完整 kustomize：`namespace` / `config`(ConfigMap) / `data`(postgres、redis、rabbitmq 的 StatefulSet 與 Service) / `support`(mailpit、zipkin) / `application`(backend、frontend) / `gateway`(nginx Deployment 與 LoadBalancer Service)。
- `k8s/overlays/aws`、`k8s/overlays/eks` 兩組 overlay，與 `deploy.ps1`／`verify.ps1` 部署腳本。
- backend 三種探針正確分開：`startupProbe` 給 Flyway migration 寬鬆的 `failureThreshold: 30`，`readinessProbe` 與 `livenessProbe` 各自對應 actuator 端點。
- backend 資源配額 `requests: 500m/1Gi`、`limits: 2/2Gi`。
- `OutboxPublisher` 以 `SELECT ... FOR UPDATE SKIP LOCKED` 處理併發，多副本安全。
- 訂單明細已快照商品資料（`order-item-product-snapshot`），拆庫後不需跨服務查商品。

### 未完成

- `k8s/base/application.yaml` 的 backend `replicas: 1`，水平擴展尚未啟用。
- `AdvisoryLockRunner` 僅存在於設計文件，程式碼未實作；4 個排程任務目前沒有任何互斥保護。
- 無 HorizontalPodAutoscaler、無 PodDisruptionBudget、無明確的滾動更新策略。
- 無 Kafka、無 Flink、無微服務拆分。

### 與既有文件的衝突（本規格取代之）

`docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md` 有兩處與現況或本規格不一致，以本規格為準：

1. 該文件規劃 backend `replicas: 2` 與 `requests: 256Mi/250m`；實際 manifest 為 `replicas: 1` 與 `500m/1Gi`。**採用實際 manifest 的資源配額**（256Mi 對 Spring Boot 容易 OOM），副本數由 P1 決定。
2. 該文件設計以 Postgres `pg_try_advisory_lock` 實作 `AdvisoryLockRunner` 解決排程重複執行。**本規格改用 Redisson**，理由見「分散式鎖」一節。該設計不實作。
3. 該文件規劃 postgres 使用 Deployment 加 PVC（「單 replica 已足夠，非 StatefulSet」）；實際 manifest 中 postgres、redis、rabbitmq 三者皆為 StatefulSet 搭配 headless Service 與 `volumeClaimTemplates`（分別為 10Gi／2Gi／5Gi）。**以實際 manifest 為準**，不回退為 Deployment。

上述三處差異應在 P1 完成後回頭更新 `2026-08-21` 文件，或於其開頭標註已被本規格取代，避免後續工作誤讀。

### 執行環境

24 邏輯核心、31.6 GB RAM、720 GB 可用空間。

Rancher Desktop 提供 kubectl v1.36.3 與 helm，容器引擎為 moby。k3s 叢集可用，單節點
`mocuo`（control-plane，v1.36.3+k3s1）。

已知環境注意事項：docker CLI 的 context 預設可能指向未執行的 Docker Desktop（`desktop-linux`），
需切換為 `default` 才能連到 Rancher Desktop 的 moby daemon。此點須寫入部署腳本的前置檢查。

## 階段切分

每個階段結束都必須是可運作、可壓測、可展示的系統。

| 階段 | 主題 | 內容 | 對應目標 |
|---|---|---|---|
| P1 | 上 K8s 與水平擴展 | 啟動叢集、部署現有單體、`replicas` 1 → 3、量測負載分配 | 負載平衡 |
| P2 | 分散式鎖 | Redisson 保護 4 個排程任務；K8s Lease 對照實作 | 分散式鎖 |
| P3 | 自動擴縮 | HPA、PodDisruptionBudget、滾動更新零中斷、尖峰流量曲線 | 負載平衡 |
| P4 | Kafka 與第一次拆分 | 導入 Kafka 作為服務間事件匯流排，抽出 purchase-service 與獨立 DB | 大數據、微服務 |
| P5 | 拆分完成與最終一致性 | order／platform 分離，database per service，Saga 補償，analytics 簡易讀取模型 | 微服務 |
| P6 | Flink 即時大屏 | Flink on K8s，秒級 GMV、每秒訂單數、熱門商品 Top N | 大數據 |

三個關鍵順序決定：

- **Kafka 放在 P4 而非 P6。** 拆服務時本來就需要服務間事件匯流排，此時導入最自然；拖到 P6 等於在 P4／P5 先用 RabbitMQ 搭一座之後要拆掉的橋。
- **P1 刻意不修任何 bug。** 單體原封不動搬上 K8s、副本數調成 3，然後觀察排程任務重複執行。問題必須被真實觀察到並留下證據，P2 才有意義。
- **Docker Compose 保留不刪。** K8s 為主環境，Compose 作為輕量開發環境與 CI 整合測試環境（CI 跑完整 K8s 過慢）。

### 本規格與實作計畫的關係

本規格是涵蓋 P1 至 P6 的**路線圖**，範圍大於單一份實作計畫。實作計畫依階段逐份產出，沿用既有的
`docs/superpowers/plans/` 慣例，一個階段一份，完成後再產出下一份。

這樣安排的理由：後期階段的設計細節會被前期階段的量測結果改寫。例如 P3 的擴縮策略取決於 P1 量到的
Pod 就緒時間；若 Pod 需要 60 秒才 Ready，HPA 的設定方式會與 15 秒的情況完全不同。先寫死後期計畫
只會產生需要重寫的文件。

下一份要產出的是 P1 的實作計畫。

## 設計

### 服務邊界與資料歸屬

切分依據是擴縮需求不同，這是微服務在 K8s 上的核心價值。

| 服務 | 併入模組 | 專屬 database | 副本 | 負責的排程任務 |
|---|---|---|---|---|
| purchase-service | `flashsale`、`inventory` | `purchase_db`：搶購活動、庫存、outbox | 3–10（HPA 主要對象） | `InventoryReconciliationScheduler` |
| order-service | `order`、`payment`、`notification` | `order_db`：訂單、明細快照、付款、通知 | 2–4 | `PaymentTimeoutScheduler`、`NotificationRetryScheduler` |
| platform-service | `identity`、`catalog`、`admin` | `platform_db`：使用者、商品主檔、API 稽核 | 2 | `ApiAuditRetentionScheduler` |
| analytics-service | 全新 | `analytics_db`：CQRS 讀取模型與聚合結果（P5 由 Kafka consumer 寫入，P6 改由 Flink 寫入） | 1–2 | 無 |

`identity` 併入 platform-service 而非獨立。登入尖峰確實隨搶購同時到來，但 JWT 以本地公鑰驗章、不呼叫 platform-service，因此尖峰只影響登入本身。若日後成為瓶頸再拆出，屆時拆分已是熟練動作。

Redis 由三個服務共用：purchase-service 用於庫存預扣計數器，全部服務用於分散式鎖。Redis 在此是協調中介而非業務資料庫，共用不違反 database per service。

### common 模組的處理

現有 `common` 有 31 個檔案（`config`／`exception`／`messaging`／`metrics`／`web`）。判準是**共用「怎麼做」，不共用「做什麼」**。

- **抽成 `platform-common` Gradle 子專案**：例外處理、metrics、web 過濾器、追蹤設定。這些是純技術基礎設施、無業務語意。
- **`messaging`（Outbox）不共用，各服務各自保留一份**：每個服務的 outbox 表在各自的 database，schema 與事件型別皆不同。共用的 outbox 抽象會綁死三個服務的發佈時機，複製一份的耦合成本低於抽象化。

### 跨服務資料需求

| 需求 | 單一 DB 時的做法 | 拆分後的做法 |
|---|---|---|
| 訂單顯示商品名稱與價格 | JOIN `catalog` | 已由 `order_items` 商品快照解決，無需改動 |
| 建單驗證使用者身分 | JOIN `users` | JWT 內含 userId，以公鑰本地驗章，不呼叫 platform-service |
| 逾時取消回補庫存 | 同一 DB 交易 | 事件驅動：order-service 發 `order.cancelled`，purchase-service 消費並回補，**消費端必須冪等** |
| 後台儀表板跨服務統計 | 單一 DB 可全查 | CQRS 讀取模型，見下 |

後台儀表板目前查詢「搶購請求數、訂單狀態分佈、趨勢圖、各活動庫存摘要」，資料拆分後散在三個 database。解法是 analytics-service 訂閱 Kafka 上的全部事件，建立專為查詢優化的讀取模型，儀表板改查 analytics-service。

此解法與 P6 的即時大屏是同一套機制：後台儀表板查歷史聚合、大屏查即時聚合，兩者都由同一條 Kafka 事件流餵出。Kafka 因此不是為了大數據硬塞進來的元件，而是拆分微服務後的必然需求。

為避免儀表板在 P5 到 P6 之間長期損壞，P5 先實作簡易讀取模型（Kafka consumer 直接寫入 Postgres 聚合表）頂住，P6 再換成 Flink 產出。

### 負載平衡與水平擴展（P1）

流量路徑與負載平衡發生的層級：

```
瀏覽器 ─HTTPS 8443→ nginx Service（LoadBalancer，Rancher Desktop servicelb）
                      └→ nginx Pod ×1
                           └ proxy_pass http://backend:8080
                                └→ backend Service（ClusterIP 虛擬 IP）  ← 負載平衡在此
                                     └ kube-proxy iptables 規則隨機選取
                                          └→ backend Pod ×3
```

**不需要在 nginx 設定 upstream 池。** `proxy_pass http://backend:8080` 解析到的是 Service 的 ClusterIP —— 一個生命週期內不變的虛擬 IP。kube-proxy 在核心層將封包分配到後端 Pod，Pod 增減時 nginx 無需任何變更。這是 Kubernetes 與傳統 Nginx 負載平衡最關鍵的差異。

已知注意事項：nginx 在啟動時解析一次 DNS 並快取。ClusterIP 穩定，故正常運作無虞；但若 Service 被刪除重建導致 ClusterIP 改變，需重啟 nginx Pod。此點寫入部署腳本的注意事項。

P1 量測三件事：

1. `replicas` 1 與 3 的吞吐、p95、p99。沿用既有 k6 腳本，同樣直接壓 backend 而不經 nginx 與 TLS，與現有 benchmark 的邊界保持一致才具可比性。
2. **負載是否平均**：比對各 Pod 的 `http_server_requests` 計數。預期不會完全均勻，因 iptables 模式為隨機選取而非輪詢；此發現本身即為報告內容。
3. **單一 Pod 從建立到 Ready 的時間**（容器啟動、Spring Boot 初始化、Flyway migration、readiness 通過）。此數字決定 P3 的 HPA 是否有意義。

### 自動擴縮（P3）

預期會得到一個反直覺但真實的結論：**CPU-based HPA 對秒殺場景反應過慢**。Pod 從建立到 Ready 需數十秒，而秒殺尖峰可能僅持續十秒，擴容完成時活動已結束。

因此 P3 實作並比較兩種策略：

- **CPU-based HPA**：完整實作，並量測其反應延遲，記錄它來不及的事實。這是負面結果，但必須誠實呈現。
- **活動前預先擴容**：依搶購活動開始時間提前調整副本數。真實電商的雙十一即採此策略，不依賴 HPA 臨場反應。

同時加入 PodDisruptionBudget 與明確的滾動更新策略，驗證更新期間服務不中斷。

### 分散式鎖（P2）

現有 5 個 `@Scheduled` 排程任務在 `replicas: 3` 下的行為：

| 排程任務 | 頻率 | 多副本下的後果 |
|---|---|---|
| `OutboxPublisher` | `fixedDelay = 500` | 安全。已用 `FOR UPDATE SKIP LOCKED`，DB 行鎖保證單一消費 |
| `PaymentTimeoutScheduler` | `fixedDelay = 30000` | **重複回補庫存**。同一筆逾時訂單被各副本各取消一次，庫存數量憑空增加 |
| `NotificationRetryScheduler` | `fixedDelay = 60000` | 重複寄送通知，使用者收到多封相同信件 |
| `InventoryReconciliationScheduler` | `fixedDelay = 60000` | 多副本同時比對並修正 Redis 與 DB，結果不可預測 |
| `ApiAuditRetentionScheduler` | `cron = 0 0 3 * * *` | 重複刪除，多半無害但浪費資源 |

`PaymentTimeoutScheduler` 的重複回補會直接破壞既有的「不超賣」保證，是最嚴重的一項。

`OutboxPublisher` 使用 `SKIP LOCKED` 而非分散式鎖是正確的：**並非所有互斥都該用分散式鎖**，能用資料庫行鎖解決的不需要外部協調服務。此對比本身即為設計文件與作品集的內容之一。

**實作方式：Redisson**

4 個排程任務改為以 `RLock.tryLock(0, leaseTime, TimeUnit.SECONDS)` 包覆，**等待時間為 0**：取不到鎖直接跳過本次執行，不排隊等待。排隊會導致各副本輪流重複執行同一批資料，違背互斥的目的。

不採用先前文件設計的 Postgres advisory lock，理由：拆分為 database per service 後，三個服務使用三個不同的 database，advisory lock 無法跨服務互斥，最終仍須改為 Redis 方案。直接採用 Redisson 可省去一次改寫。代價是失去「先用最簡方案、撞到邊界再升級」的演進敘事，此取捨為刻意選擇。

**必須驗證的深水區**：

- **看門狗自動續期與固定 leaseTime 的取捨**：任務執行時間超過 leaseTime 時，鎖被其他節點取得，形成兩個節點同時執行。需實測此情境。
- **持鎖節點故障**：以 `kubectl delete pod --force` 強制刪除持鎖的 Pod，量測鎖實際釋放所需時間。
- **fencing token**：鎖已過期但舊節點仍在執行時，以單調遞增 token 讓下游拒絕過期請求。這是 Redlock 爭議的核心。
- **Redis 單點故障**：Redis 為單副本 StatefulSet，其故障將導致所有排程停擺。此取捨須明確記錄。

**K8s Lease 對照實作**：以 Kubernetes Lease API 實作一份 leader election，套用於同一組排程任務，與 Redisson 版本比較故障模式（鎖釋放延遲、對外部元件的依賴、網路分割時的行為）。此為對照實驗，不取代 Redisson 作為主線方案。

### 事件流與串流運算（P4、P6）

**Topic 與分區策略**。分區鍵的選擇決定順序保證的範圍：

| Topic | 分區鍵 | 理由 |
|---|---|---|
| `purchase.reserved` | `activityId` | 同一活動的事件進入同一分區以保證有序；不同活動平行處理 |
| `order.created`、`order.cancelled` | `orderId` | 同一訂單的狀態變更有序 |
| `inventory.restocked` | `activityId` | 與預扣事件同分區，避免對帳時序錯亂 |

Kafka 與 RabbitMQ 並存：RabbitMQ 保留既有的工作佇列用途（非同步建單），Kafka 承擔事件流用途（多下游訂閱、可重播）。不強行合併為單一中介軟體。

**Flink 作業，依難度遞增實作**：

1. **每秒訂單數**：滾動視窗（tumbling window）。最簡單，用於建立環境與信心。
2. **即時 GMV 與熱門商品 Top N**：滑動視窗搭配 KeyedState。
3. **亂序與 exactly-once 驗證**：刻意注入遲到事件、刻意重啟 TaskManager，驗證結果仍正確。

**正確性驗證方法**：對同一批事件，Flink 算出的 GMV 必須等於直接查詢 `order_db` 的 `SUM`。這是唯一能證明串流運算正確的方式，也是 P6 的驗收標準。

## 量測與證據策略

沿用既有的 `docs/portfolio/data/benchmark-results.json` 格式，每階段新增一組對照數據。

| 階段 | 對照組 | 實驗組 | 關鍵指標 |
|---|---|---|---|
| P1 | `replicas: 1` | `replicas: 3` | 吞吐、p95、p99、各 Pod 請求分配比例 |
| P2 | 未加鎖 | Redisson | 重複執行次數（應為 0）、庫存正確性、鎖競爭延遲 |
| P3 | 固定副本 | HPA | 擴容反應時間、尖峰期間錯誤率 |
| P5 | 單體 | 微服務 | 端到端延遲（預期上升）、跨服務追蹤完整性 |
| P6 | 直接查 DB | Flink 即時聚合 | 大屏延遲、與 DB 查詢結果的數值一致性 |

P5 的結果預期為負面：拆分微服務後端到端延遲必然上升。誠實記錄此代價比僅呈現微服務的好處更具說服力。

## 已知取捨與風險

| 項目 | 取捨 | 影響 |
|---|---|---|
| Postgres、Redis、Kafka 皆單副本 | 本機資源有限，不做高可用 | 任一元件故障即全站停擺；須在報告中明確標示 |
| 選 Flink 而非 Kafka Streams | 使用者明確選擇業界標準方案 | 學習曲線較陡，且額外消耗 3–4 GB 記憶體；P6 的作業分三步遞增以降低風險 |
| 選 Redisson 而非 Postgres advisory lock | 省去 P5 的一次改寫 | 失去「最簡方案撞到邊界再升級」的敘事；P2 即引入 Redis 對鎖的依賴 |
| 拆為 3 個服務而非 6 個 | 依擴縮需求切分，降低搬遷量 | 服務內仍有多個 bounded context，非教科書式微服務 |
| K8s 與 Compose 並存 | CI 跑完整 K8s 過慢 | 兩套環境定義需同步維護，存在漂移風險 |
| 資源總量估計 12–16 GB | 31.6 GB 實體記憶體 | P6 同時運行 Flink 與完整壓測時可能吃緊，屆時需降低副本數 |

## 驗收

### P1

- `kubectl get pods -n flashsale` 全數 Running 且 Ready，backend 三個 Pod 皆 Ready。
- 瀏覽器經 `https://localhost:8443` 完成一次完整購買流程，行為與 Compose 環境一致。
- 產出 `replicas` 1 與 3 的壓測對照數據，含各 Pod 的請求分配比例。
- 於 `kubectl logs` 中觀察到至少一個排程任務在多個 Pod 同時執行，並留下紀錄作為 P2 的問題證據。

### P2

- 存在一個失敗測試：模擬多副本且未加鎖時，`PaymentTimeoutScheduler` 對同一筆逾時訂單重複回補庫存，導致庫存數量錯誤。
- 加入 Redisson 後該測試轉為通過，且 `kubectl logs` 顯示同一次排程僅由單一 Pod 執行。
- `AdvisoryLockRunner` 不實作；`2026-08-21-k3s-rancher-desktop-deployment-design.md` 的對應段落標註為已由本規格取代。
- 完成四項深水區驗證並記錄結果：鎖續期、持鎖節點強制刪除後的釋放時間、fencing token、Redis 故障時的行為。
- K8s Lease 版 leader election 可運作，並產出與 Redisson 的故障模式對照表。

### P3

- HPA 可依 CPU 指標自動擴縮，並量出從負載上升到新 Pod Ready 的完整延遲。
- 以模擬雙十一尖峰的 k6 曲線驗證 HPA 的反應是否足夠，結果無論正負皆記錄。
- 滾動更新期間，壓測不出現 5xx 與連線中斷。

### P4

- Kafka 部署於叢集內，purchase-service 獨立運行並使用專屬 database。
- 一次搶購的 Zipkin 追蹤可跨 purchase-service 與剩餘單體，鏈路完整。
- 驗證同一 `activityId` 的事件落在同一分區且順序正確。

### P5

- 三個服務各自擁有獨立 database，程式碼中無任何跨庫查詢。
- 混沌測試：搶購流程進行中強制刪除 order-service 的 Pod，恢復後資料達到最終一致，無訂單遺失或庫存錯誤。
- 後台儀表板改由 analytics-service 提供資料，功能與拆分前一致。

### P6

- Flink 三個作業皆於叢集內運行，即時大屏可顯示秒級 GMV、每秒訂單數與熱門商品 Top N。
- 對同一批事件，Flink 產出的 GMV 與 `order_db` 的 `SUM` 查詢結果一致。
- 注入遲到事件與重啟 TaskManager 後，聚合結果仍正確。
