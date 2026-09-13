# FlashSale K8s 微服務化與即時數據平台設計規格

- 以現有 FlashSale 為基礎擴充，不另開專案；Kubernetes 取代 Docker Compose 成為主環境，Compose 保留為開發與 CI 用。
- 單體拆成 3 個業務微服務（purchase／order／platform）加 1 個 analytics-service，切分依據是擴縮需求而非 bounded context 數量。
- 每個服務一個獨立 Postgres database，禁止跨庫查詢；一致性靠 Transactional Outbox 與事件最終一致。
- 分散式鎖用 Redisson 保護 4 個排程任務，另以 Kubernetes Lease API 實作一份 leader election 做故障模式對照。
- 事件流用 Kafka，串流運算用 Flink on K8s，產出即時 GMV 大屏與後台儀表板的 CQRS 讀取模型。
- 分六個階段推進（P1–P6），每階段結束都是可運作、可壓測、可展示的系統，並留下 before／after 對照數據。
- 不做 service mesh、不做 GitOps、不做離線批次（Spark／資料湖），不改動既有業務規則。

**進度：P1、P3 已完成（2026-09-13）。** P1 的量測推翻了本規格原先對 P3 的假設，該節已依實測
重寫；P3 的驗收證據見文中「P3 — 已完成」與 [水平擴展與自動擴縮](../../portfolio/scaling-and-autoscaling.md)。
修訂紀錄見文末。P2（分散式鎖）計畫見
[`2026-09-13-week8-p2-distributed-lock.md`](../plans/2026-09-13-week8-p2-distributed-lock.md)。

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
  **P1 已實測驗證**：三副本下 117 筆事件全部只發佈與消費一次。
- 訂單明細已快照商品資料（`order-item-product-snapshot`），拆庫後不需跨服務查商品。

P1（2026-09-13）新增：

- backend `replicas: 3`，並宣告 `RollingUpdate` 策略（`maxSurge: 1`、`maxUnavailable: 0`）
  與 `preStop` hook。
- `build-local.ps1` 依叢集回報的 `containerRuntimeVersion` 選擇建置工具，containerd 與
  moby 皆可運作。
- `deploy.ps1` / `verify.ps1` 的期望 Pod 數取自工作負載宣告的副本數，不再寫死為 1。
- `load-tests/k8s/`：叢集內的 k6 壓測 Job、端到端驗證 Job，以及每個 Pod 的請求分配量測腳本。
- **`k8s/` 首次真正部署成功。** 在此之前這套流程從未實際執行過
  （見 `docs/portfolio/k3s-baseline.md` 的證據狀態）。

### 未完成（P1 結束後的狀態）

- 4 個排程任務仍然沒有任何互斥保護。P1 已實測其後果：三副本下庫存被回補到 87 而總庫存只有 30，
  「不超賣」保證完全失效。見 [排程重複執行證據](../../portfolio/scheduler-duplication-evidence.md)。
- 無 HorizontalPodAutoscaler、無 PodDisruptionBudget。
- **壓測工具無法量到水平擴展的價值。** 現有的 `purchase-flow.js` 是固定併發（100 VU、每 VU 一次
  迭代），量的是固定壓力下的延遲，不是系統的吞吐上限。副本數增加不會讓負載產生器送出更多請求，
  因此 P1 量到「三副本比單副本慢 41%」。這是量測方法的限制，不是水平擴展的結論。
- 無 Kafka、無 Flink、無微服務拆分。

P1 已完成的項目（`replicas: 3`、RollingUpdate 策略、preStop hook、首次實際部署）
見上節「已完成」。

### 與既有文件的衝突（本規格取代之）

`docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md` 有三處與現況或本規格不一致，以本規格為準：

1. 該文件規劃 backend `replicas: 2` 與 `requests: 256Mi/250m`；實際資源配額為 `requests: 500m/1Gi`、`limits: 2/2Gi`（256Mi 對 Spring Boot 容易 OOM）；副本數經 P1 實測後定為 `replicas: 3`。
2. 該文件設計以 Postgres `pg_try_advisory_lock` 實作 `AdvisoryLockRunner` 解決排程重複執行。**本規格改用 Redisson**，理由見「分散式鎖」一節。該設計不實作。
3. 該文件規劃 postgres 使用 Deployment 加 PVC（「單 replica 已足夠，非 StatefulSet」）；實際 manifest 中 postgres、redis、rabbitmq 三者皆為 StatefulSet 搭配 headless Service 與 `volumeClaimTemplates`（分別為 10Gi／2Gi／5Gi）。**以實際 manifest 為準**，不回退為 Deployment。

上述差異已於 P1 收尾時處理：`2026-08-21` 文件開頭已加上「已被本規格取代」的標註，逐項列出四處差異（含排程互斥的解法）。

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
| P1 | 上 K8s 與水平擴展 | 部署現有單體、`replicas` 1 → 3、量測負載分配、滾動更新零中斷 | 負載平衡 |
| P2 | 分散式鎖 | Redisson 保護 4 個排程任務；K8s Lease 對照實作 | 分散式鎖 |
| P3 | 壓測方法論與自動擴縮 | 飽和式壓測（`ramping-arrival-rate`）、量出吞吐上限、HPA、PDB | 負載平衡 |
| P4 | Kafka 與第一次拆分 | 導入 Kafka 作為服務間事件匯流排，抽出 purchase-service 與獨立 DB | 大數據、微服務 |
| P5 | 拆分完成與最終一致性 | order／platform 分離，database per service，Saga 補償，analytics 簡易讀取模型 | 微服務 |
| P6 | Flink 即時大屏 | Flink on K8s，秒級 GMV、每秒訂單數、熱門商品 Top N | 大數據 |

三個關鍵順序決定：

- **Kafka 放在 P4 而非 P6。** 拆服務時本來就需要服務間事件匯流排，此時導入最自然；拖到 P6 等於在 P4／P5 先用 RabbitMQ 搭一座之後要拆掉的橋。
- **P1 不預先修復待觀察的缺陷。** 單體原封不動搬上 K8s、副本數調成 3，然後觀察排程任務重複執行。
  問題必須被真實觀察到並留下證據，P2 才有意義。此原則只適用於「要觀察的缺陷」；
  擋住 P1 本身的基礎設施問題該修就修（P1 實際修了三個：建置腳本不相容於本機 runtime、
  端到端腳本缺少必要的請求主體、滾動更新掉請求）。
- **Docker Compose 保留不刪。** K8s 為主環境，Compose 作為輕量開發環境與 CI 整合測試環境（CI 跑完整 K8s 過慢）。

### 本規格與實作計畫的關係

本規格是涵蓋 P1 至 P6 的**路線圖**，範圍大於單一份實作計畫。實作計畫依階段逐份產出，沿用既有的
`docs/superpowers/plans/` 慣例，一個階段一份，完成後再產出下一份。

這樣安排的理由：後期階段的設計細節會被前期階段的量測結果改寫。例如 P3 的擴縮策略取決於 P1 量到的
Pod 就緒時間；若 Pod 需要 60 秒才 Ready，HPA 的設定方式會與 15 秒的情況完全不同。先寫死後期計畫
只會產生需要重寫的文件。

已產出：P1（`2026-09-13-week8-p1-k8s-scale-out.md`，已執行完畢）與 P2
（`2026-09-13-week8-p2-distributed-lock.md`，待執行）。P3 的計畫在 P2 完成後才寫。

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

P1 量測三件事（**皆已完成**，完整數據見
[`k8s-scale-out-results.json`](../../portfolio/data/k8s-scale-out-results.json)）：

1. **`replicas` 1 與 3 的吞吐、p95、p99。** 實測 p95 為 274.97ms 對 388.03ms，吞吐
   6.48 對 6.50 req/s。三副本較慢，但這是固定併發壓測的限制而非擴展的結論，P3 處理。
2. **負載是否平均。** 實測 137 / 147 / 109（34.9% / 37.4% / 27.7%），最大偏離完美均分 12.2%。
   如預期不均勻 —— iptables 模式是隨機選取而非輪詢，樣本數越小偏差越明顯。
3. **單一 Pod 從建立到 Ready 的時間。** 實測 **10 秒**，1 → 3 全部 Ready 為 11.2 秒。
   這個數字推翻了本規格原先對 P3 的假設（見「壓測方法論與自動擴縮」一節）。

P1 額外發現並修正的一項：`maxUnavailable: 0` **不等於**零中斷。滾動更新期間實測掉了 0.60%
的請求，因為「從 Endpoints 移除」與「收到 SIGTERM」是並行的，kube-proxy 規則尚未傳播完畢時
流量仍會被導向終止中的 Pod。加上 `preStop: sleep 5` 與 `terminationGracePeriodSeconds: 30`
之後為 0%。

### 壓測方法論與自動擴縮（P3）

**本節於 P1 完成後重寫。** 原本的內容預設「Pod 從建立到 Ready 需數十秒，因此 CPU-based HPA
對秒殺場景一定來不及」。P1 實測的數字是 **10 秒**（擴容 1 → 3 全部 Ready 為 11.2 秒），
該前提不成立，依它推導出的結論也一併作廢。

#### 為什麼要先修壓測，而不是直接做 HPA

P1 量到三副本的 p95 比單副本慢 41%（275ms → 388ms），吞吐量持平（6.48 vs 6.50 req/s）。
這不是「水平擴展沒用」的證據，而是**壓測設計量不到水平擴展**的證據：

`purchase-flow.js` 使用 `per-vu-iterations`，100 個 VU 每個跑一次迭代。負載總量由 VU 數量
決定，與後端有幾個副本無關。副本再多也不會有更多請求進來，只會多三個 JVM 在同一台機器上
競爭 CPU 與記憶體 —— 所以延遲變差而吞吐不變，完全符合預期。

在這個前提下做 HPA，量出來的數字會有同樣的問題：HPA 會觸發、Pod 會起來，然後吞吐不變，
因為負載產生器根本沒有加壓。**先修壓測，HPA 的數據才有意義。**

#### P3 的兩個部分

**第一部分：能量到飽和點的壓測**

改用 k6 的 `ramping-arrival-rate` executor：固定到達率、逐步加壓，直到系統開始崩。
量的是「吞吐上限在哪裡、p95 在哪個 RPS 開始劣化」，而不是固定併發下的延遲。

需要的配套：

- **分端點標記**（`tags: { leg: 'purchase' }`）。目前 `http_req_duration` 混合了註冊、登入、
  搶購與輪詢四種端點，看不出是哪一段先崩。`load-tests/benchmark/purchase-load.js` 已有此做法，
  可直接借用。
- **預先產生 token**，把認證流量移出量測區間。`load-tests/benchmark/prepare.js` 已經這樣做。
- **同時記錄下游指標**：Postgres 連線數、Hikari 池使用率、Redis 延遲。P1 的結果強烈暗示瓶頸
  在下游而非 backend；若確實如此，那才是「為什麼加副本沒用」的真正答案，也是 P4／P5 拆分
  purchase-service 與獨立資料庫的直接動機。
- **明確控制連線數，並把它記進結果檔。** 這是量測負載平衡最容易踩的陷阱：
  **kube-proxy 是每條 TCP 連線分配一次，不是每個請求分配一次。** iptables 的 DNAT 發生在連線
  建立的那一刻，之後這條 keep-alive 連線上的所有請求都會落在同一個 Pod。因此「3 個 VU 各發
  1000 個請求」只會產生 3 條連線，看起來會像負載完全不平衡，但那是量測方式造成的假象。
  P1 的 137 / 147 / 109 之所以可解讀，正是因為 100 個 VU 各自持有連線。飽和式壓測要嘛使用
  `noConnectionReuse`，要嘛記錄實際連線數。

用這套工具重跑 `replicas` 1 / 3 / 5，畫出 RPS 對 p95 的曲線。**這才是回答「水平擴展有沒有用」
的證據**，P1 的數據只能回答「固定併發下三副本比較慢」。

#### 施壓端必須與受測系統在同一個網路平面

P1 把 k6 Job 放在叢集內是刻意的決定。Week 8 期間在 Compose benchmark 上取得了支持這個決定的
直接證據：從 Windows 經 host port 施壓時，300 條瞬間到達的新連線有 25–30% 在 TCP 握手階段就被
**host port 發布層**拒絕（`ECONNREFUSED`），Tomcat 從未看到那些連線，但 k6 會把它們記成請求
失敗。同一個 backend 容器、同一支腳本，改從容器網路內施壓則是 0 失敗。

這個失真正是既有 `performance-report.md` 中「300 VU 連線被拒、反覆試了三輪」那一節的真正成因，
也解釋了為什麼調大 Tomcat `accept-count` 從未可靠地解決它（調的參數不在瓶頸路徑上），以及為什麼
「停掉同機其他工作負載」有效（同機干擾正是透過這一層作用）。`collect.ps1` 已改為在 compose
網路內以容器執行 k6。

**2026-09-13 補充（P3 的施壓端配置已改）**：重新量測後確認同樣的限制也存在於 k3s 的 NodePort，
因為兩者都經過 Rancher Desktop 在 Windows 端的中繼行程。量到的上限是
**瞬間同時連線約 210 條**、**持續新連線 1,200/秒 0 失敗** —— 被卡的是「同時建立中的連線數」，
不是連線速率。

因此 P3 的施壓端不是「一律放叢集內」，而是依情境分成兩條路：arrival-rate 模型或會重用連線的
腳本放 Windows（時鐘準確，VM 內的時鐘實測快約 3.5%），刻意製造 >200 條同時新連線的情境
才放叢集內。細節見 [量測環境的時鐘準確度](../../portfolio/wsl2-clock-accuracy.md)。

**第二部分：HPA 與 PDB**

有了飽和點之後才做：

- **CPU-based HPA**：以第一部分量出的飽和點設定閾值，量測從負載上升到新 Pod 就緒的完整延遲。
  既然 Pod 只要 10 秒，HPA **有機會**跟得上持續數十秒的尖峰 —— 這要實測，不要預設答案。
- **活動前預先擴容**：依搶購活動開始時間提前調整副本數，與 HPA 做對照。真實電商的雙十一
  採此策略。即使 HPA 來得及，預先擴容仍然更穩妥，因為它不依賴指標採集與決策的延遲。
- **PodDisruptionBudget**：確保自願性中斷（節點維護、叢集升級）時維持最低可用副本數。

滾動更新的零中斷驗證**已在 P1 完成**，不屬於 P3。P1 發現 `maxUnavailable: 0` 本身不足夠
（實測掉 0.60% 請求），需搭配 `preStop` hook 讓 Endpoints 變更有時間傳播；修正後為 0%。

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

| 階段 | 對照組 | 實驗組 | 關鍵指標 | 狀態 |
|---|---|---|---|---|
| P1 | `replicas: 1` | `replicas: 3` | 吞吐、p95、p99、各 Pod 請求分配比例 | **已完成**，見 [`k8s-scale-out-results.json`](../../portfolio/data/k8s-scale-out-results.json) |
| P1 | 無 `preStop` | 有 `preStop` | 滾動更新期間的請求失敗率 | **已完成**：0.60% → 0% |
| P2 | 未加鎖 | Redisson | 重複執行次數（應為 0）、庫存正確性、鎖競爭延遲 | **已完成**，見 [分散式鎖的故障模式](../../portfolio/distributed-lock-failure-modes.md) 與 [兩種分散式鎖的對照](../../portfolio/lock-mechanism-comparison.md) |
| P3 | `replicas` 1 / 3 / 5 | — | 吞吐上限、RPS 對 p95 的曲線、瓶頸位置 | **已完成**，見 [`k8s-saturation-results.json`](../../portfolio/data/k8s-saturation-results.json) 與 [水平擴展與自動擴縮](../../portfolio/scaling-and-autoscaling.md) |
| P3 | 固定副本 | HPA | 擴容反應時間、尖峰期間錯誤率 | **已完成**：決策 12 秒、就緒 43 秒，但擴容動作本身觸發節點級重啟風暴（見上方 P3 驗收） |
| P5 | 單體 | 微服務 | 端到端延遲（預期上升）、跨服務追蹤完整性 | 待做 |
| P6 | 直接查 DB | Flink 即時聚合 | 大屏延遲、與 DB 查詢結果的數值一致性 | 待做 |

P1 的結果中有兩項是負面的，都保留在證據檔中，不做修飾：三副本在固定併發下比單副本慢 41%
（量測方法的限制，P3 要解決），以及 `maxUnavailable: 0` 不足以達成零中斷（已由 preStop 修正）。

P5 的結果預期同樣為負面：拆分微服務後端到端延遲必然上升。誠實記錄此代價比僅呈現微服務的好處更具說服力。

## 已知取捨與風險

| 項目 | 取捨 | 影響 |
|---|---|---|
| Postgres、Redis、Kafka 皆單副本 | 本機資源有限，不做高可用 | 任一元件故障即全站停擺；須在報告中明確標示 |
| 選 Flink 而非 Kafka Streams | 使用者明確選擇業界標準方案 | 學習曲線較陡，且額外消耗 3–4 GB 記憶體；P6 的作業分三步遞增以降低風險 |
| 選 Redisson 而非 Postgres advisory lock | 省去 P5 的一次改寫 | 失去「最簡方案撞到邊界再升級」的敘事；P2 即引入 Redis 對鎖的依賴 |
| 拆為 3 個服務而非 6 個 | 依擴縮需求切分，降低搬遷量 | 服務內仍有多個 bounded context，非教科書式微服務 |
| K8s 與 Compose 並存 | CI 跑完整 K8s 過慢 | 兩套環境定義需同步維護，存在漂移風險 |
| 資源總量估計 12–16 GB | 31.6 GB 實體記憶體 | P6 同時運行 Flink 與完整壓測時可能吃緊，屆時需降低副本數 |
| k6 與叢集共用同一台機器 | 不另外準備施壓機 | 壓力來源與受測系統互相競爭 CPU。P1 三副本較慢有一部分來自此；P3 的飽和式壓測會讓這個影響更明顯，屆時需記錄 k6 Pod 自身的資源使用率，必要時降低 backend 副本數上限 |
| 排程互斥暫時缺席 | P1 刻意不修，先取得證據 | **目前部署的三副本環境存在已知的庫存超賣缺陷**，在 P2 完成前不可視為可用系統 |

## 驗收

### P1 — 已完成（2026-09-13）

- `kubectl get pods -n flashsale` 全數 Running 且 Ready，backend 三個 Pod 皆 Ready。**通過**
- 經 `https://localhost:8443` 完成完整購買流程，行為與 Compose 環境一致。**通過**：
  30 買家搶 10 件，checks 160/160，10 筆訂單、20 筆 SOLD_OUT、庫存歸零、無殘留 PENDING。
- 產出 `replicas` 1 與 3 的壓測對照數據，含各 Pod 的請求分配比例。**通過**
- 觀察到排程任務在多個 Pod 重複執行，並留下紀錄作為 P2 的問題證據。**通過，且後果比預期嚴重**：
  30 筆訂單中 27 筆被三個副本各處理一次，`available_quantity` 被回補到 87 而總庫存只有 30。
  見 [排程重複執行證據](../../portfolio/scheduler-duplication-evidence.md)。
- 滾動更新期間壓測不出現連線中斷。**初次未通過（0.60%），加上 preStop hook 後通過（0%）**

### P2

- 存在一個失敗測試：模擬多副本且未加鎖時，`PaymentTimeoutScheduler` 對同一筆逾時訂單重複回補庫存，導致庫存數量錯誤。
- 加入 Redisson 後該測試轉為通過，且 `kubectl logs` 顯示同一次排程僅由單一 Pod 執行。
- `AdvisoryLockRunner` 不實作；`2026-08-21-k3s-rancher-desktop-deployment-design.md` 的對應段落標註為已由本規格取代。
- 完成四項深水區驗證並記錄結果：鎖續期、持鎖節點強制刪除後的釋放時間、fencing token、Redis 故障時的行為。
- K8s Lease 版 leader election 可運作，並產出與 Redisson 的故障模式對照表。

### P3 — 已完成（2026-09-13）

證據見 [水平擴展與自動擴縮](../../portfolio/scaling-and-autoscaling.md) 與原始資料
[`k8s-saturation-results.json`](../../portfolio/data/k8s-saturation-results.json)。

- 存在一套 `ramping-arrival-rate` 的飽和式壓測，能量出系統的吞吐上限而非固定併發下的延遲。
  **通過**：`load-tests/k8s/saturation.js` 使用 k6 `ramping-arrival-rate` executor，
  `load-tests/k8s/run-saturation.ps1` 封裝完整流程（縮放、種資料、壓測、下游取樣、收檔）。
- 壓測分端點標記，認證流量以預先產生的 token 移出量測區間。**通過**：`saturation.js` 對
  accept（`tags: { leg: 'accept' }`）與 poll（`tags: { leg: 'poll' }`）分別標記；
  `run-saturation.ps1` 呼叫 `load-tests/benchmark/prepare.js` 預先產生買家 token，
  壓測本體只打搶購與輪詢端點。
- 產出 `replicas` 1 / 3 / 5 的「RPS 對 p95」曲線，明確回答水平擴展在什麼負載區間才開始有
  價值。**通過，但有一格量不到**：曲線涵蓋 11 個（replicas、targetRate）組合；
  `replicas=1`、`targetRate=900` 因系統本身撐不住（開放模型下所需 VU 數隨延遲發散，
  `droppedIterations` 無法歸零）而記為 `unachievedRates`，不強行湊進曲線。**回答**：
  到達率 150／300 時三種副本數表現幾乎相同，水平擴展看不出價值；到達率 600 時一副本
  p95=130.0ms、三副本 p95=6.9ms（慢約 18.8 倍）；到達率 900 時一副本完全無法穩定運作，
  三副本 70.9ms、五副本 61.0ms 仍健康。價值的轉折點落在到達率 300 到 600 之間，且一旦
  超過就是數量級差距。
- 同時記錄下游指標（Postgres 連線數、Hikari 池使用率、Redis 延遲），指認真正的瓶頸位置。
  **通過**：`sample-downstream.ps1` 背景取樣 `hikariActive`／`hikariPending`／
  `pgBackends`／`reservationMaxMs`。瓶頸指認：一個副本時是 Postgres 連線池（HikariCP 30
  條上限，900 rps 下 15 個連續樣本、63.7 秒維持 `hikariPending` 131–176）；三、五個副本在
  測試範圍內未觀察到同樣的持續排隊，但 `pgBackends` 隨副本數線性成長（92 對 152），逼近
  Postgres `max_connections`——五副本已迫使該值從預設 100 調高到 300 才能量測，是 P4/P5
  拆分 purchase-service 與獨立資料庫的直接動機。
- HPA 可依 CPU 指標自動擴縮，並量出從負載上升到新 Pod 就緒的完整延遲；以實測判斷它是否
  跟得上尖峰，結果無論正負皆記錄。**通過，結果是正負參半**：CPU 確實從 60% 一路衝到
  425%，HPA 在 12 秒內下達 rescale 決策、新副本於 43 秒內就緒——**決策**跟得上 30 秒的
  爬升期。但**擴容動作本身**在單節點、CPU 有限的測試叢集上引發節點級 CPU 搶佔，5 個新
  Pod 同時冷啟動疊加在忙碌的舊 Pod 之上，觸發至少 7 個 Pod（含 3 個未參與擴容、原本健康的
  舊 Pod）確認的 liveness 重啟——第 8 個 Pod（`qj9cn`）只查得到探測失敗事件，重啟與否未
  確認，且其中 4 次確認重啟的證據隨 Pod 在後續 scale-down 中被刪除而消失。可直接觀察、
  不受此影響的是：8 個 Pod 於 16:42:08–16:42:19 同時 NotReady，構成一次真實但短暫的
  服務中斷。這次量測本身也未通過 `analyze-saturation.mjs` 的資料品質關卡
  （`droppedIterations>0`），因此絕對延遲數字（p95 2972.9ms）只能當方向性證據，不是乾淨
  的容量數字。
- 與「活動前預先擴容」做對照，給出在本專案情境下的建議策略。**通過**：同一到達率（900）、
  同一目標副本數（8）下，HPA 運行中擴容 p95=2972.9ms、963 個 failedRequests，預先擴容到
  8 則是 p95=434.2ms、0 個 failedRequests（兩者皆未通過資料品質關卡，僅供方向性比較）。
  建議：本專案情境下優先採用「依活動時間預先擴容」，而非依賴 HPA 現場反應——HPA 的判斷
  沒有問題，問題在單節點環境下擴容動作本身的代價；預先擴容不依賴指標採集與決策延遲，也
  不會觸發同時冷啟動造成的搶佔。
- PodDisruptionBudget 生效，自願性中斷時維持最低可用副本數。**通過**：`minAvailable: 2`
  的 PDB 在 2 副本（無餘裕）時拒絕驅逐（`Cannot evict pod as it would violate the pod's
  disruption budget`），在 3 副本（一個餘裕）時允許驅逐（`"status":"Success","code":201`）
  ——相同請求、唯一變數是副本數，結果從拒絕翻成接受。

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

---

## 修訂紀錄

### 2026-09-13 — P1 完成後的修訂

P1 的實測結果推翻了本規格的一項假設，並讓兩處描述過時。修訂如下：

| 章節 | 原本 | 改為 | 依據 |
|---|---|---|---|
| 自動擴縮（P3） | 「Pod 從建立到 Ready 需數十秒，CPU-based HPA 對秒殺一定來不及」 | 整節重寫為「壓測方法論與自動擴縮」：先做飽和式壓測，再做 HPA | 實測 Pod 就緒為 10 秒，原假設不成立 |
| 階段切分 | P3 含「滾動更新零中斷」 | 移至 P1（已完成） | P1 已實作並驗證，且發現 `maxUnavailable: 0` 不足夠 |
| 階段切分 | 「P1 刻意不修任何 bug」 | 「P1 不預先修復待觀察的缺陷」 | 原文會誤導成連擋路的基礎設施問題都不能修 |
| 現況盤點 | 未完成清單含 `replicas: 1`、無滾動更新策略 | 移至已完成；新增「壓測工具量不到水平擴展」 | P1 完成 |
| 量測與證據策略 | 全部為待做 | P1 兩列標為已完成並附數據連結；P3 拆為兩列 | P1 完成 |

**最重要的一項修訂不是數字對錯，而是問題的層次改變了。** 原本 P3 的題目是「HPA 來不來得及」，
現在的題目是「**我們的壓測根本量不到水平擴展的價值**」。P1 量到三副本比單副本慢，
原因是負載總量由 VU 數量決定、與副本數無關。在修好壓測之前做 HPA，得到的數字會有同樣的問題。
