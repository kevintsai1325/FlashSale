# 分散式鎖的故障模式實測

`SchedulerLock`（Redisson `RLock`）讓四個 `@Scheduled` 排程在多副本下只執行一次，修復了
[排程重複執行造成的庫存超賣](./scheduler-duplication-evidence.md)。但「功能可用」和「知道它
在故障時怎麼壞」是兩回事。這份文件記錄四項故障模式的實測結果。

## 量測環境與方法約束

| 項目 | 值 |
|---|---|
| 叢集 | Rancher Desktop k3s，單節點 `mocuo`，v1.36.3+k3s1 |
| backend 副本數 | 3 |
| 鎖 | Redisson `RLock`，`tryLock(0, leaseTime, SECONDS)` |
| 受測排程 | `PaymentTimeoutScheduler`（`fixedDelay = 30s`、租約 60s） |
| 量測時間 | 2026-09-13 |

**所有計時都在 Windows 這一側完成。** 同日實測發現 WSL2 VM 的時鐘快約 3.5%，
且牆鐘每約 32.5 秒被往回校正約 1.4 秒（[量測環境的時鐘準確度](./wsl2-clock-accuracy.md)）。跨 Windows 與容器兩個時鐘相減，
1.5 秒的誤差足以吃掉整個量測。因此本文件的所有時間都以 Windows 輪詢 Redis 的鎖鍵狀態取得。

**為了讓鎖被持有夠久而能觀察，實驗中以 SQL 產生 3,000 筆逾時訂單。** 正常情況下這個排程
只花數十毫秒就完成並解鎖，30 秒週期裡鎖只存在約 0.3% 的時間 —— 這本身就是一個結論，見第 5 節。

---

## 1. 持鎖副本崩潰：鎖由租約到期釋放，誤差 0.4%

在鎖被持有的當下，以 `--force --grace-period=0` 強制刪除全部三個副本（跳過 preStop，
直接 SIGKILL），再由 Windows 輪詢鎖鍵直到消失。

| 量測 | 值 |
|---|---|
| 崩潰當下的剩餘租約 | 57,825 ms |
| 鎖實際釋放耗時 | **58,033 ms** |
| 差距 | 208 ms（**0.4%**） |

持有者被 SIGKILL 後沒有任何人執行解鎖，鎖完全由 Redis 的 TTL 到期釋放，與理論吻合。

**這就是租約長度的意義：它是持有者崩潰時的損害上界。** 租約設 60 秒，代表最壞情況下這個
排程會停擺一輪（間隔 30 秒），不會永久死鎖。租約若設得更長，崩潰後停擺更久；設得更短，
則任務有可能在執行中就被別人搶走鎖。

### 中途的一個錯誤結論，以及它為什麼是錯的

第一次嘗試時，我刪除單一 Pod（`items[0]`），量到鎖在 5,947 ms 就釋放，遠短於 54,420 ms 的
剩餘租約。當時差點記成「崩潰後很快就釋放」。

實際原因是**刪到的不是持有者**：Redisson 的鎖在 Redis 裡是 hash，field 為
`<客戶端UUID>:<執行緒ID>`，**從 Redis 這側看不出持有者是哪一個 Pod**。真正的持有者處理完
3,000 筆訂單後正常解鎖，量到的其實是「任務執行時間」。

第二次改用 `kubectl scale --replicas=0`，量到 6,198 ms —— 同樣不是租約到期。原因是
`scale` 走的是優雅終止：SIGTERM → preStop `sleep 5` → 寬限期 30 秒，Pod 活得夠久把任務做完
並正常解鎖。**這反而證明了優雅關閉會正確釋放鎖**，是個正面結果，只是不是當下要量的東西。

只有第三次的 `--force --grace-period=0` 才是真正的「崩潰」。

---

## 2. 租約到期導致雙重執行：以計數器觀察，不以時間觀察

租約若短於任務執行時間，鎖會在任務還沒做完時被別的副本取得，形成雙重執行。

**這一項刻意不量時間**，改為觀察 `scheduler.lock.outcome{outcome="expired"}` 計數器 ——
計數不受時鐘回跳影響，而時間會。`SchedulerLock` 在解鎖前會檢查
`isHeldByCurrentThread()`，若已不持有就記一次 `expired` 並發出 WARN。

正常運作下的實測（3 副本、30 筆訂單、一個完整實驗週期）：

| outcome | 次數 | 意義 |
|---|---|---|
| `acquired` | 10 | 取得鎖並執行 |
| `skipped` | 2 | 因為別的副本持有而跳過 |
| **`expired`** | **0** | **沒有任何一次租約在任務執行中到期** |

`expired = 0` 表示 60 秒租約對這個任務足夠寬裕。

**`expired` 只要大於 0 就是需要立刻調查的訊號，不是統計數字** —— 它代表那段期間曾有兩個副本
同時執行同一個排程，資料可能已經被重複處理。

---

## 3. Redis 故障：整個網站停止服務，但會自動完全復原

`kubectl scale statefulset/redis --replicas=0`，觀察 60 秒。

| 指標 | Redis 正常 | **Redis 停機** | 恢復後 |
|---|---|---|---|
| backend Pod `Ready` | 3 / 3 | **0 / 3** | 3 / 3 |
| Service `backend` 的 endpoints | 3 | **0** | 3 |
| 經 Nginx 的 API 請求 | HTTP 200 | **HTTP 502** | HTTP 200 |
| 容器重啟次數 | 0 | 0 | **0** |
| 恢復耗時 | — | — | **13 秒** |

**結果比預期嚴重：不只是排程停擺，整個網站對外停止服務。**

原因是 readiness 探針的健康群組包含 Redis。Redis 一掛，三個 backend Pod 全部被判定為
not ready，kube-proxy 把它們從 Service 的 endpoints 移除，Nginx 找不到任何上游而回 502。

需要講清楚的歸因：**這個行為早於 P2，不是分散式鎖造成的。** Redis 本來就在 readiness 群組裡
（庫存預扣計數器依賴它）。P2 只是讓依賴變得更深 —— 現在排程也需要 Redis 才能運作。

正面的部分是恢復行為很乾淨：Redis 回來後 13 秒內三個 Pod 自動恢復 Ready、**容器零重啟**
（liveness 探針沒有誤殺它們）、積壓的逾時訂單也被排程處理完畢。**沒有任何人工介入。**

### 這是單副本 Redis 的代價，已知且刻意

[Week 8 設計規格](../superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md)
的「已知取捨與風險」已載明 Postgres、Redis、Kafka 皆為單副本，本機資源下不做高可用。
這次實測把「故障即停擺」這句話換成了具體數字：**0 個可用副本、HTTP 502、恢復 13 秒**。

---

## 4. fencing token：本專案不實作，以及為什麼

「鎖過期但舊持有者還在執行」是分散式鎖無法自己解決的問題 —— 鎖只能保證「同一時間只有一個
持有者」，不能阻止一個**自以為還持有鎖**的舊持有者繼續寫入。

標準解法是 fencing token：每次取得鎖時附帶一個單調遞增的號碼，下游在寫入時比對，
拒絕號碼小於已見過最大值的請求。

**本專案不實作 fencing token**，理由是下游的形狀讓它不必要：

`OrderCompensationService.compensate()` 的所有寫入都落在**同一個資料庫**。要防止過期持有者
的重複寫入，在 `Order` 加上 `@Version` 樂觀鎖就能達成等價效果 —— 過期持有者手上的實體版本
已經過時，`save()` 會拋 `OptimisticLockException` 而不是靜靜覆寫。成本遠低於引入 token
機制，而且是 JPA 原生支援。

**但這個判斷有一個明確的失效條件：** 若日後補償動作要呼叫外部系統（例如金流退款、通知第三方
物流），樂觀鎖就管不到了，fencing token 會變成必要。Week 8 P5 把服務拆成 database per service
之後，`order.cancelled` 事件會跨服務觸發庫存回補 —— 屆時要重新評估這個決定。

目前的實際防護是三層，fencing token 不在其中：

1. **租約**：持有者崩潰時損害上界為一個排程週期（實測 0.4% 誤差內準確）
2. **解鎖前檢查 `isHeldByCurrentThread()`**：避免解掉別人的鎖，這比不解鎖更危險
3. **`expired` 計數器**：租約真的在任務中到期時，事件是可觀測的而不是靜默的

---

## 5. 一個沒有預期到的觀察：鎖幾乎總是空著的

正常負載下，`expireOverduePayments` 處理完一批訂單只需數十毫秒，而排程間隔是 30 秒。
換算下來**鎖只在約 0.3% 的時間裡被持有**。

這有兩個含意：

- **「持鎖副本崩潰」的曝險窗口很小。** 崩潰要剛好落在那 0.3% 才會觸發租約到期的路徑。
  這降低了故障模式 1 的實際發生機率，但不改變它的後果。
- **這個實驗必須人為製造工作量才做得出來。** 本文件的量測是在 3,000 筆逾時訂單的條件下取得的
  （任務因此需要約 6 秒）。若只用正常的 30 筆，輪詢根本抓不到鎖被持有的瞬間。

順帶一提，3,000 筆的批次規模下鎖依然守住了：庫存 `available_quantity` 從 0 變成 3,000、
`sold_quantity` 從 5,000 變成 2,000 —— **恰好 3,000 次回補，一次都沒有重複**。

---

## 重現方式

```bash
# 產生足夠的工作量，讓鎖被持有得夠久
kubectl -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c \
  "UPDATE orders SET status='PENDING_PAYMENT', payment_due_at = now() - interval '1 hour'
   WHERE status='EXPIRED';"

# 故障模式 1：持有者崩潰（必須用 --force --grace-period=0，否則 preStop 會讓它優雅解鎖）
load-tests/k8s/lock-failure-modes.sh holder-crash

# 故障模式 3：Redis 停機
load-tests/k8s/lock-failure-modes.sh redis-down

# 觀察鎖的即時狀態
load-tests/k8s/lock-failure-modes.sh lock-state
```
