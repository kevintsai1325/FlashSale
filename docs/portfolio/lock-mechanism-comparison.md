# 兩種分散式鎖的對照：Redisson 與 Kubernetes Lease

FlashSale 的四個 `@Scheduled` 排程在多副本下需要互斥，否則會造成
[庫存超賣](./scheduler-duplication-evidence.md)。這份文件對照兩種實作方式，
兩者都在同一個三副本 k3s 叢集上實測過。

以 `app.scheduling.lock` 切換：`redisson`（預設）或 `kubernetes`。

| 項目 | 值 |
|---|---|
| 叢集 | Rancher Desktop k3s，單節點，v1.36.3+k3s1 |
| backend 副本數 | 3 |
| 租約 | 60 秒（`PaymentTimeoutScheduler`，間隔 30 秒） |
| 量測時間 | 2026-09-13 |

---

## 正確性：兩者都守住了

以完全相同的步驟跑那個造成超賣的實驗（三副本、同時刪除 Pod 讓計時器對齊、30 筆訂單一次逾時）：

| 指標 | 無鎖 | Redisson | Kubernetes Lease | 正確值 |
|---|---|---|---|---|
| `order_status_history` | 117 | **60** | **60** | 60 |
| `available_quantity` | 87（總量 30） | **30** | **30** | 30 |
| `sold_quantity` | −27 | **0** | **0** | 0 |
| 每筆訂單處理次數 | 27 筆各 3 次 | **全部恰好 1 次** | **全部恰好 1 次** | 1 |

就「能不能防止重複執行」而言，兩者沒有差別。差別在故障時的行為與運維體驗。

---

## 結構差異：這是理解一切的關鍵

| | Redisson | Kubernetes Lease |
|---|---|---|
| 互斥靠什麼 | Redis 端的 Lua 腳本原子操作 | API server 的樂觀併發控制（`resourceVersion` 過期回 409） |
| 過期怎麼發生 | Redis 的 TTL，**鎖鍵自己消失** | **物件永遠不會消失**，由讀取方計算 `renewTime + duration` 是否已過 |
| 過期判定依賴誰的時鐘 | Redis 單一時鐘 | **每個副本自己的時鐘** |
| 誰持有鎖 | `<客戶端UUID>:<執行緒ID>`，**對應不回 Pod** | `holderIdentity` 直接是 **Pod 名稱** |
| 外部相依 | Redis | API server（叢集本來就依賴它） |
| 實作成本 | 一個相依（`redisson-spring-boot-starter`） | 約 150 行純 HTTP，**零新增相依** |

「物件不會消失」這一行是最容易誤解的地方。實測畫面很清楚 —— 持有者被 SIGKILL 之後：

```
NAME                              HOLDER                     RENEW
scheduler-expireoverduepayments   backend-66445f5875-khhks   2026-09-13T11:03:21.697219Z
```

`holderIdentity` 仍然指著一個**已經死掉的 Pod**，`renewTime` 凍結在崩潰那一刻。
Lease 沒有「釋放」這個動作，只有「上一個持有者看起來已經失聯，我接手」。

---

## 故障模式：持有者崩潰

兩邊都以 `--force --grace-period=0` SIGKILL 全部副本（優雅終止會讓 Pod 活著把任務做完並正常
解鎖，量不到這個路徑）。

### Redisson

| 量測 | 值 |
|---|---|
| 崩潰當下的剩餘租約 | 57,825 ms |
| **鎖鍵從 Redis 消失** | **58,033 ms** |
| 誤差 | 208 ms（0.4%） |

### Kubernetes Lease

| 量測 | 值 |
|---|---|
| 租約長度 | 60 秒 |
| 凍結的 `renewTime` 起算，**新副本接手** | **88.8 秒** |

### 這兩個數字量的不是同一件事

必須講清楚，否則會得出「Lease 慢 30 秒」的錯誤結論：

- Redisson 量的是「**鎖鍵消失**」—— 這是 Redis 單方面的行為，與任何副本無關。
- Lease 量的是「**新副本接手**」—— 因為 Lease 物件不會消失，「接手」是唯一可觀察的事件。

Lease 的 88.8 秒 = 60 秒租約 + 最多 30 秒的排程間隔（新副本只在自己的 tick 才會去檢查）
+ Pod 啟動時間。**Redisson 若量同一件事也會落在 58–88 秒之間** —— 它的下一個持有者同樣要
等自己的 tick。兩者在實際效果上是相當的。

**真正的結論不是誰比較快，而是：兩者的接手延遲都由「租約長度 + 排程間隔」決定，
而不是由鎖的實作決定。** 想縮短就得縮短租約或加密排程頻率，換機制沒有幫助。

---

## 故障模式：協調服務掛掉

### Redis 停機（實測）

| 指標 | 正常 | **Redis 停機** | 恢復後 |
|---|---|---|---|
| backend Pod `Ready` | 3 / 3 | **0 / 3** | 3 / 3 |
| 對外 API | HTTP 200 | **HTTP 502** | HTTP 200 |
| 容器重啟 | 0 | 0 | **0** |
| 恢復耗時 | — | — | **13 秒** |

**整個網站停止服務**，不只是排程停擺。原因是 readiness 探針的健康群組包含 Redis
（這早於分散式鎖，Redis 本來就是庫存預扣計數器的依賴）。恢復行為很乾淨，無需人工介入。

### API server 不可用（未實測，僅推論）

**這一項沒有實測**，因為在單節點 k3s 上停掉 API server 等同於毀掉整個叢集，無法在不破壞
實驗環境的前提下觀察。以下是基於程式碼的推論，標示清楚以免被當成量測結果：

- readiness 群組**不包含** API server，因此 Pod 應該仍會被判定為 ready，HTTP 服務繼續。
- `KubernetesLeaseSchedulerLock` 的 `get()` 會拋例外，被記為 `outcome=error` 並跳過該次執行。
  排程停擺但不會崩潰。
- 但 API server 不可用時，Deployment 無法調度、滾動更新無法進行、`kubectl` 全部失效 ——
  **叢集本身已經不能運作**，排程能不能跑是次要問題。

這是 Lease 相對於 Redis 的一個結構性優勢：**它不引入新的故障域**。API server 掛掉時，
你已經有更大的麻煩了；Redis 掛掉則是多了一個獨立的單點。

---

## 時鐘：Lease 在這台機器上有一個真實風險

Lease 的過期判定是「**讀取方**比較 `now` 與 `renewTime + leaseDurationSeconds`」，
用的是**每個副本自己的時鐘**。Redisson 的 TTL 則完全由 Redis 單一時鐘決定。

本專案的執行環境實測過：**WSL2 VM 的時鐘比實際時間快約 3.5%，且牆鐘每約 32.5 秒
被 Hyper-V 往回校正一次、每次約 1.4 秒**（Windows 主機端的時鐘速率已驗證為正確）。
完整的量測與診斷見 [量測環境的時鐘準確度](./wsl2-clock-accuracy.md)。

1.4 秒相對於 60 秒租約是 2.3%，實務上不足以造成雙重持有。而且在單節點 k3s 上，
三個副本跑在**同一個 VM 的同一個時鐘**，會一起偏、不會彼此相對偏移 —— 本機環境量不到這個風險。

但它在多節點的正式環境是**結構性風險**：

- 若某個節點的時鐘**走快**，其上的副本會提早認為租約已過期而搶鎖 —— 而真正的持有者還在執行。
- 租約越短，時鐘誤差的相對影響越大。若把租約縮到 5 秒，1.4 秒的跳動就是 28%。
- 這不是假想：本機環境的 VM 時鐘就實測走快 3.5%。正式環境靠 NTP 把各節點拉到同一個時間，
  **Lease 的正確性因此建立在「NTP 正常運作」這個前提上**，而那是一個外部相依。

**Redisson 沒有這個問題**：所有副本問的是同一個 Redis 的同一個 TTL，副本自己的時鐘不參與判定。

---

## 運維體驗：Lease 明顯勝出的一點

排查「現在是誰持有鎖」時：

```bash
# Kubernetes Lease
kubectl -n flashsale get leases
NAME                              HOLDER                     AGE
scheduler-expireoverduepayments   backend-66445f5875-4x9tl   53s
scheduler-reconcileinventory      backend-66445f5875-4x9tl   53s
scheduler-retryduenotifications   backend-66445f5875-4x9tl   52s
```

一眼看出是哪個 Pod。**Redisson 做不到這件事** —— 它的鎖 hash 只記
`<客戶端UUID>:<執行緒ID>`，那個 UUID 是 `RedissonClient` 的實例識別，沒有任何地方把它
對應回 Pod 名稱。

這個差異在實驗中造成了具體代價：測「持有者崩潰」時，Redisson 版第一次刪錯 Pod
（刪了非持有者），量到的是任務執行時間而不是租約到期，差點得出錯誤結論。
Lease 版直接 `kubectl get leases` 就知道要刪哪一個。

---

## 結論與建議

**本專案維持 Redisson 為預設**，理由：

1. **時鐘**。Lease 的過期判定依賴各副本的本地時鐘，而本機環境的時鐘會往回跳。
   這是這台機器特有的問題，但它是真的。
2. Redis 已經是既有相依（庫存預扣計數器），使用它不新增故障域。
3. Redisson 的 API 更完整（可重入、公平鎖、看門狗續期），未來若需要這些能力不必換機制。

**但如果是新專案、且沒有既有的 Redis**，Kubernetes Lease 是更好的起點：

1. **零新增相依、零新增基礎設施**。約 150 行純 HTTP。
2. **不引入新的故障域**。API server 掛了的話，你的問題比排程大得多。
3. **運維可見性好得多**。`kubectl get leases` 直接看到持有者是哪個 Pod。

需要注意的兩個前提：叢集的時鐘要可信（正式環境通常有 NTP，不像本機環境），
以及要願意自己處理 RBAC 與 API 呼叫的細節 —— 本專案實作時就踩到
`MicroTime` 需要微秒精度（秒精度會被 API server 以 400 拒絕）這種只有看文件看不出來的細節。

---

## 兩個實作上的教訓

### 1. 「取不到鎖」與「鎖壞掉」必須是可區分的結果

Lease 實作的第一版把 HTTP 回應簡化成 `statusCode == 201`，於是 `409 Conflict`（別的副本
搶先，正常）與 `400 Bad Request`（請求本身是錯的）被歸為同一類，都記成 `skipped`。

結果是**排程連續 12 次完全沒有執行，日誌一行錯誤都沒有，指標顯示一切「正常跳過」**。
真正的原因（MicroTime 格式）要靠手動打 API 才看得到。

現在的實作把回應分成三類：成功、409（正常競爭失敗）、其他（拋例外並帶上狀態碼與回應內容）。

**在分散式協調的程式碼裡，把「沒搶到」和「機制故障」合併成一個 boolean，會讓系統在故障時
表現得像在正常運作。**

### 2. 用一個掛著相同 ServiceAccount 的 curl Pod 除錯

一輪「改程式 → 建映像 → 部署」要十分鐘以上。改用一個掛著 `flashsale-backend` ServiceAccount
的 `curl` Pod 手動打 API，驗證縮到十秒，而且直接看到 API server 的原始錯誤訊息 ——
那是應用程式日誌不會顯示的東西。順帶也驗證了 RBAC 的最小權限確實生效（`DELETE → HTTP 403`）。

```bash
kubectl -n flashsale run lease-probe --restart=Never --image=curlimages/curl:8.10.1 \
  --overrides='{"spec":{"serviceAccountName":"flashsale-backend"}}' --command -- sleep 3600
kubectl -n flashsale exec lease-probe -- sh -c '
  T=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
  curl -s --cacert /var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
    -H "Authorization: Bearer $T" \
    https://kubernetes.default.svc/apis/coordination.k8s.io/v1/namespaces/flashsale/leases'
```
