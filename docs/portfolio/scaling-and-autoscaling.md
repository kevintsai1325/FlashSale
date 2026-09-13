# 水平擴展與自動擴縮：飽和式壓測、瓶頸指認、HPA 與預先擴容

P1 用固定併發（`per-vu-iterations`）量到「三副本比單副本 p95 慢 41%、吞吐持平」，這個結果一度
被讀成「水平擴展沒用」。P3 先修壓測方法論，再重新量測，結論完全相反：**水平擴展確實有用，
只是舊工具量不出來。** 這份文件記錄修法、重新量到的曲線、瓶頸位置、HPA 的實測反應延遲，
以及它跟預先擴容的對照。

所有數字的來源：除非另有標註，皆取自 [`docs/portfolio/data/k8s-saturation-results.json`](./data/k8s-saturation-results.json)
（P3 的曲線、飽和點、下游瓶頸證據）。HPA 與 PDB 的實測另外標明出處。

---

## 1. 為什麼要先修壓測，而不是直接信任 P1 的結論

P1 的壓測腳本（`purchase-flow.js`）用 k6 的 `per-vu-iterations` executor：100 個 VU、每個
VU 跑一次迭代。**負載總量由 VU 數量決定，與後端有幾個副本完全無關。** 副本再多，也不會有
更多請求被送進來——只會多出幾個 JVM 在同一台機器上競爭 CPU 與記憶體。

在這個前提下，「三副本 p95 比單副本慢 41%（275ms → 388ms）、吞吐量持平（6.48 對 6.50 req/s）」
（數字來源：[`k8s-scale-out-results.json`](./data/k8s-scale-out-results.json)，P1 的封閉模型
量測）是完全符合預期的結果，但它回答的問題是「固定併發下三副本比較慢嗎」，不是「水平擴展有
沒有用」。這是一個**封閉模型（closed model）造成的量測假象**，不是水平擴展本身無效的證據
——封閉模型下系統的總輸入本來就不會因為後端擴展而增加，自然量不出擴展的價值。

P3 改用 k6 的 `ramping-arrival-rate` executor（開放模型，`load-tests/k8s/saturation.js`）：
到達率是外生設定的，逐步爬升，系統跟不上就會排隊、延遲上升。這才是能回答「水平擴展有沒有
用」的量測方式。重新量測的結果（見下一節）直接推翻了 P1 的表面結論：在同一個到達率（900）下，
accept p95 從一個副本完全撐不住，變成三個副本 70.9 ms、五個副本 61.0 ms。

---

## 2. `replicas` 1 / 3 / 5 的 RPS 對 p95 曲線

`achievedRps` 是 k6 `http_reqs` 的整體速率，每個 iteration 打兩個請求（accept 的 POST、poll
的 GET），所以數值大約是設定的到達率（`targetRate`）的 1.7–2 倍——例如 `targetRate=150` 時
`achievedRps≈266.6`，不是 150。比較擴展有沒有用，看的是同一個 `targetRate` 下不同副本數的
`acceptP95Ms`，不是 `achievedRps`（定義見 JSON 的 `units` 區塊）。

| replicas | targetRate | achievedRps | accept p95 (ms) |
|---|---|---|---|
| 1 | 150 | 266.6 | 7.9 |
| 1 | 300 | 516.4 | 6.2 |
| 1 | 600 | 1016.1 | **130.0** |
| 1 | 900 | *未達成——見下* | *未達成* |
| 3 | 150 | 266.6 | 8.6 |
| 3 | 300 | 516.4 | 6.0 |
| 3 | 600 | 1016.1 | 6.9 |
| 3 | 900 | 1515.5 | 70.9 |
| 5 | 150 | 266.5 | 8.7 |
| 5 | 300 | 516.4 | 6.2 |
| 5 | 600 | 1016.3 | 6.8 |
| 5 | 900 | 1515.9 | 61.0 |

**`replicas=1`、`targetRate=900` 這一格量不到**，原因不是施壓端不夠力，而是系統本身撐不住：
在開放模型下，所需的 VU 數大約是「到達率 × 延遲」，延遲一旦發散，所需 VU 數也跟著發散，
沒有任何有限的 `-MaxVUs` 能讓 `droppedIterations` 歸零。加碼過程（`preAllocatedVUs`/`maxVUs`
從 300/1200 一路加到 1500/6000）讓掉的迭代從 13526 降到 1461，但從未到 0——這是系統的極限，
不是施壓端的極限，因此記在 `unachievedRates` 裡，不是硬湊進曲線。

三個飽和點（同一副本數下，測過且維持健康的最高到達率）：

| replicas | 飽和點 (RPS) | accept p95 (ms) |
|---|---|---|
| 1 | 1016.1（`targetRate=600`；900 打不進去） | 130.0 |
| 3 | 1515.5（`targetRate=900`） | 70.9 |
| 5 | 1515.9（`targetRate=900`） | 61.0 |

### 水平擴展在什麼負載區間才開始有價值

**在 150 與 300 這兩個到達率，三種副本數的表現幾乎完全一樣**（p95 都在個位數毫秒，差異在
量測雜訊範圍內）——這個負載區間本來就沒有壓力，加副本沒有意義，也看不出差異。

**價值從 600 開始出現，而且差距很大**：一個副本的 accept p95 是 130.0 ms，三個副本是
6.9 ms——**慢了約 18.8 倍**。到了 900，一個副本已經完全無法穩定運作（見上），三個副本
70.9 ms、五個副本 61.0 ms 仍然健康。

**結論：水平擴展在到達率逼近單一副本的連線池容量上限之前完全看不出價值；一旦超過這個點，
差距是數量級的，而不是百分比的。** 對這個系統而言，那個轉折點落在到達率 300 到 600 之間。
P1 用封閉模型量到的「三副本更慢」，是因為那套壓測從來沒有把負載推到這個轉折點以上。

---

## 3. 瓶頸指認

下游指標由 `load-tests/k8s/sample-downstream.ps1` 在壓測期間背景取樣（約每 2 秒隨機打一個
副本的 metrics 端點），欄位為 HikariCP 的 `hikariActive`／`hikariPending`、Postgres 的
`pgBackends`、Redis 庫存預扣延遲 `reservationMaxMs`。

### `replicas=1`：Postgres 連線池是唯一瓶頸

`replicas=1`、`targetRate=900`（打不進去的那次嘗試）的下游取樣：31 個樣本裡，`hikariActive`
貼著 30（HikariCP 每副本上限）、`pgBackends` 峰值 31、`reservationMaxMs` 全程為 0。取
`hikariPending > 0` 為門檻，最長的連續取樣區間是 **15 個樣本、跨越 63.7 秒**，這段期間
`hikariPending` 落在 **131–176（中位數 167）**——這是持續性的排隊，不是單一尖峰。

對照它自己健康的飽和點（`targetRate=600`）：同樣的門檻下，最長連續區間只有 **5 個樣本、
跨越 8.7 秒**，數值 1–19（中位數 15）；`hikariPending` 曾經衝到 173 的峰值，但那是**孤立的
單一取樣尖峰**，不在任何連續排隊區間內。也就是說，即使是「健康」的那一格，連線池已經在
偶爾喘不過氣，只是還沒有形成持續排隊。

`reservationMaxMs` 兩次都是 0，代表 Redis 的庫存預扣本身沒有變慢。**結論：一個副本在高負載
下的瓶頸明確是 Postgres 連線池（HikariCP 30 條上限）被打滿、執行緒在等連線，不是 Redis
或 backend 運算本身。**

### `replicas=3` 與 `replicas=5`：測試範圍內沒有觀察到同樣的排隊

`replicas=3`、`targetRate=900`（其飽和點）：47 個樣本裡 `hikariPending` **全程為 0**；
`hikariActive` 在取樣到的那個副本上偶爾頂到 30（平均 5.66，因為取樣器每次只打三個副本裡
隨機一個，平均值本來就會被稀釋），但沒有伴隨排隊。`pgBackends` 峰值 92（≈3×30），距調高後
的 300 上限尚有餘裕。

`replicas=5`、`targetRate=900`（**重跑版本**，說明見下）：同樣 47 個樣本、
`hikariPending` 全程為 0；`hikariActive` 峰值 30、平均 3.26（五個副本分攤，稀釋更明顯）。
`reservationMaxMs` 峰值僅 48.9 ms，Redis 依然很快。但 `pgBackends` 峰值 **152**——非常接近
`5 × 30 = 150`，這個數字本身就是一個關鍵發現（見下）。

取樣是每約 2 秒隨機挑一個副本，看不到「多個副本同時全滿」的瞬間，也看不到兩次取樣之間可能
出現又消失的短暫排隊。能下的結論是「在測試涵蓋的速率內沒有觀察到持續性排隊」，不是「連線池
完全沒有壓力」。

**為什麼這一格是重跑的，以及這對比較的意義**：這是本文件曲線裡唯一一個跟其他十格用不同
施壓端設定量出來的點。其餘每一格都用 `run-saturation.ps1` 的預設
`-PreAllocatedVUs 300 -MaxVUs 1200`；`replicas=5`、`targetRate=900` 最初也用同樣的預設值
跑過一次，但那次只掉了 15 個 iteration、p95 僅 85.2 ms——跟 `replicas=1`、`targetRate=900`
那次「無論怎麼加碼到 1500/6000 都掉 1461、14468，從未歸零」（見第 2 節）比起來，15 對
14468 相差三個數量級。這個差距本身就是判斷依據：15 個掉的 iteration 判定為施壓端自己的
VU 配額不夠撐住這個到達率（generator headroom 不足），不是系統的容量極限；因此改用
`-PreAllocatedVUs 800 -MaxVUs 3000` 重新跑過一次，`droppedIterations` 歸零，p95 變成
61.0 ms，本文件的曲線與 `downstreamPeaks` 用的都是這個重跑版本，原本 300/1200 那次的結果
不採用（原始判斷記在 JSON 的 `downstreamPeaks["5"].rerunReason`）。**對比較的意義**：
VU 配額理論上只決定施壓端撐不撐得住既定的到達率，不直接改變後端實際處理每個請求的延遲，
所以把這一格的 accept p95 拿來跟同一到達率下的 `replicas=3`（也是 900）比較仍然合理；但
它是曲線裡唯一一個沒有用預設設定量到的點，讀者若要做更嚴格的逐格比較，應該知道這個差異
存在，而不是預設十一格都用同一套施壓端設定量出來的。

### 真正的天花板：Postgres `max_connections`，在任何 CPU 或吞吐瓶頸之前就先撞到

Postgres 預設 `max_connections=100`。每個 backend 副本的 HikariCP 上限是 30 條連線。
五個副本理論上需要 150 條同時連線——**還沒開始壓測，光是種測試資料就先撞牆**：
`psql: FATAL: sorry, too many clients already`。這不是壓測量出來的瓶頸，是一個在任何 CPU
或吞吐上限之前就先出現的硬性天花板。

為了讓五副本的量測能夠進行，`k8s/base/data.yaml` 把 `max_connections` 調高到 300。
**這代表本文件的整條曲線都是在這個調高後的設定下量出來的**，不是預設值——細節見第 7 節的
誠實聲明。`replicas=5` 的 `pgBackends` 峰值 152 也證實了這不是形式上的調整：那已經超過原本
100 的預設上限，是這次量測能不能跑起來的先決條件。

### 這正是 P4／P5 拆分 purchase-service 與獨立資料庫的動機

三、五副本在測試涵蓋的速率內還沒有出現持續排隊，但 `pgBackends` 峰值（92 對 152）隨副本數
線性成長，逼近一個共用、單一的 Postgres 實例。每加一個副本就多綁 30 條連線，這條路線不能
無限延伸——五副本已經逼近調高後的上限的一半，繼續加副本遲早會重演「連 psql 都進不來」的
情境，而下一次撞到的天花板會更難靠調參數繞過去。**這正是把購買流程拆成獨立服務、搭配獨立
資料庫（P4/P5）的直接依據**：問題不在 backend 運算本身，而在所有副本共用同一個資料庫連線
池這個結構。

---

## 4. HPA 的完整反應延遲

實驗設定：`k8s/autoscaling/hpa.yaml`，CPU 閾值 60%（對應單一 Pod 用到 300m，因為
`requests.cpu=500m`），`scaleUp` 無穩定視窗（`stabilizationWindowSeconds: 0`）、每 15 秒
最多加 100%，`minReplicas=3`、`maxReplicas=8`。從 3 副本、900 rps 到達率開始加壓
（`-SkipScaling`，`run-saturation.ps1` 不干預副本數，讓 HPA 自己決定）。

**一個先講清楚的限制**：`load-tests/k8s/watch-scaling.ps1` 自身的取樣週期（實測約
2.3–3.6 秒）跟 HPA 的實際反應時間是同一個數量級，這支工具本身回答不了「四個時間點精確落在
哪裡」——它只能告訴你「狀態已經變了」，抓不到「剛好變的那一刻」。下表的時間點因此**不是**從
`timeline.csv` 直接讀出來的，而是改用 Kubernetes 自己記錄的事件（`kubectl get events` 的
`SuccessfulRescale`、Pod 的 `status.conditions[Ready].lastTransitionTime`）與 k6 摘要 JSON
的 `startedAt` 欄位重建；`timeline.csv` 只用來確認「有沒有發生」與大致趨勢。

| # | 事件 | 時間戳（UTC） | 來源 |
|---|---|---|---|
| 1 | 負載開始（k6 從 50 rps 往 900 爬升） | 16:39:10.927 | k6 摘要 JSON 的 `startedAt`（毫秒精度） |
| 2 | CPU 首次超過 60% | 16:39:23（上界） | 由事件 3 反推——HPA 一定在這一刻或更早就觀察到過閾值；`watch-scaling.ps1` 的取樣空窗（16:39:23.26 仍顯示 cpu=2%，下一筆 16:39:26.47 已是 161%）本身量不到確切跨越時刻 |
| 3 | `desiredReplicas` 首次改變（3→6） | 16:39:23 | `kubectl get events` 的 `SuccessfulRescale`（K8s API 事件時間戳，秒精度） |
| 4 | 新副本 `readyReplicas` 到位 | 16:39:54 | 新 Pod 的 `status.conditions[Ready].lastTransitionTime`（K8s 自己記錄） |

（第二次 rescale 6→8 發生在 16:39:38，對應第二批新 Pod 在 16:40:13 轉 Ready，Pod 建立到
就緒約 35 秒。）

**反應延遲：**

- 負載開始 → `desiredReplicas` 首次改變：約 **12 秒**
- 負載開始 → 新副本就緒：約 **43 秒**

CPU 確實跨過了 60% 閾值，一路衝到 161% → 295% → 392% → 425%（`timeline.csv` 記錄），
`scaleUp.stabilizationWindowSeconds: 0` 生效：HPA 在偵測到過閾值的同一個控制迴圈內就下了
rescale 決策。整段反應（決策 12 秒、新副本就緒 43 秒）都發生在 k6 30 秒的 ramp 還沒跑完之前
——單看決策速度，HPA 的控制迴圈跟得上這個秒殺流量的爬升速度。

### 但完整時間軸揭露了更嚴重的問題：擴容動作本身觸發了服務中斷

`timeline.csv` 顯示：三個舊 Pod 在 16:39:40–46 開始 readiness 探測逾時（累積到
`failureThreshold=3` 後被移出 Ready，容器本身還活著，還沒被殺）。真正的**重啟**發生在更晚：
`kubectl get events` 顯示 16:41:18 之後，原本已經健康跑了 4 分鐘以上的 3 個舊 Pod
（`bp6hj`／`kf6cl`／`bsb9g`），以及 5 個新 Pod 裡的 4 個（`2pn97`／`zxnq6`／`64dcv`／
`lctf8`），都查得到明確的 `Killing: failed liveness probe` 事件——**至少 7 次確認重啟**。
第 5 個新 Pod（`qj9cn`）只查得到 readiness／liveness 探測**失敗**的事件，沒有查到對應的
`Killing` 事件，它有沒有真的被重啟過**沒有被確認**。這 7 次確認重啟裡有 4 次
（`bsb9g`／`zxnq6`／`64dcv`／`lctf8`）在後續還原環境的 `kubectl scale --replicas=3`
把這些 Pod 刪掉之後，就從任何事後查詢的重啟計數裡消失了——`restartCount` 是 Pod 物件自己
的欄位，Pod 一旦被刪除，這個數字就跟著不見，不代表重啟沒發生過，只代表證據隨 Pod 一起消失。
不受這個問題影響、可以直接從 `timeline.csv` 讀到的是：8 個 Pod 在 16:42:08–16:42:19 之間
**同時 NotReady**——這是一次真正的服務中斷，不是單純變慢。

機制：`kubectl top node` 在重啟風暴期間量到節點 CPU 使用率 78%（單節點 k3s，24 邏輯核心）。
backend `limits.cpu=2`，8 個副本理論上限就要 16 核心，加上 5 個新 Pod 同時冷啟動（JVM class
loading／JIT／Spring context 初始化本身是重 CPU 的階段）疊加在 3 個已經在 161–295% 忙碌的舊
Pod 之上，把節點整體 CPU 需求推到連 kubelet 執行 liveness probe 這種輕量 HTTP 呼叫都排不到
時間片——**連完全沒有被擴容影響、原本健康的舊 Pod 也被拖下水重啟**。

**「跟不跟得上」的結論，正負都講**：HPA 的**判斷**沒有跟不上——CPU 越過閾值到下達 rescale
決策只花 12 秒，遠快於 30 秒的爬升期。但**擴容這個動作本身**，在這個單節點、CPU 資源有限的
測試叢集上是自傷性的：同時冷啟動多個 JVM 造成的節點級 CPU 搶佔，觸發了至少 7 個 Pod
（含 3 個未參與擴容、原本健康的舊 Pod）已確認的 liveness 重啟，加上第 8 個 Pod 重啟與否未
獲確認；8 個 Pod 同時 NotReady、構成一次真實但短暫的服務中斷，這一點是直接觀察到的，不在
爭議範圍內。這是一個負面但誠實的結果。

---

## 5. HPA 與「活動前預先擴容」的對照

同一到達率（900）、同一目標副本數（8，即 HPA 最終擴到的上限）：

| 指標 | HPA 運行中擴容（3→8，含重啟風暴） | 預先擴容到 8（無 HPA，無擴容延遲） |
|---|---|---|
| accept p95 | 2972.9 ms | 434.2 ms |
| accept 中位數 | 1062.6 ms | 74.4 ms |
| accept max | 10001.6 ms | 2457.1 ms |
| failedRequests | 963 | 0 |
| non202Responses | 538 | 0 |
| unexpected5xx | 0 | 0 |
| droppedIterations | 9522 | 100 |
| `analyze-saturation.mjs` 判定 | **REJECTED** | **REJECTED** |

**兩次都沒有通過資料品質關卡**（`droppedIterations>0`，關卡沒有被放寬）。這代表兩邊的
`achievedRps`／p95 都不能當作乾淨的系統容量量測，而是「施壓端本身也撐不住」的訊號——本節的
數字是關於**反應窗口**的方向性證據，不是容量數字。即使如此，兩邊 `droppedIterations` 相差
近百倍（9522 對 100）、accept p95 相差近 7 倍，這個量級差距本身仍是有意義的訊號：即使兩邊
都不「乾淨」，預先擴容那組是輕微超載，HPA 那組是服務中斷等級的超載。

### 本專案情境下的建議策略

**優先選預先擴容，而非依賴 HPA 現場反應。** 理由：

1. HPA 的**決策**沒有問題（60% 閾值有效、12 秒內下判斷），問題在**擴容動作本身的代價**在
   單節點、CPU 資源有限的環境裡被放大到失控——這正是本專案目前的部署環境。
2. 秒殺活動的開始時間通常是已知的（真實電商的雙十一即採此策略），依活動時間提前調整副本數
   完全不依賴指標採集與決策延遲，也不會觸發同時冷啟動造成的搶佔。
3. 即使把叢集換成多節點正式環境，冷啟動的 CPU 需求會分散到不同節點，HPA 的自傷效應可能會
   減輕，但預先擴容仍然更穩妥：它不依賴指標系統本身在尖峰時仍然可靠這個前提。

若日後仍要讓 HPA 在這類場景派上用場，可考慮（本任務範圍內不做，僅記錄方向）：把
`scaleUp.policies` 從「每 15 秒 100%」改成更保守的階梯（例如一次只加 1–2 個 Pod），把冷啟動
的 CPU 尖峰攤開；放寬 readiness/liveness probe 的 timeout，避免 CPU 被榨乾時把健康 Pod
錯殺；以及認知到單節點測試環境本身放大了這個問題，多節點正式環境的表現需要重新實測，不能
直接套用這裡的數字。

---

## 6. PodDisruptionBudget 的證據

`k8s/base/availability.yaml` 定義 `minAvailable: 2` 的 PDB，選中 `app: backend`。以完全
相同的驅逐請求對照兩種副本數：

### 2 副本、無餘裕——驅逐被拒

```
$ kubectl --context rancher-desktop -n flashsale get pdb backend
NAME      MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
backend   2               N/A               0                     23s

$ kubectl --context rancher-desktop create --raw "/api/v1/namespaces/flashsale/pods/backend-5b4578d658-88c4z/eviction" -f eviction.json
Error from server (TooManyRequests): Cannot evict pod as it would violate the pod's disruption budget.
```

Exit code 1。Pod 驅逐後仍是 `Running`、0 重啟——請求對正在跑的 Pod 沒有任何效果。

### 3 副本、一個餘裕——驅逐成功

```
$ kubectl --context rancher-desktop -n flashsale get pdb backend
NAME      MIN AVAILABLE   MAX UNAVAILABLE   ALLOWED DISRUPTIONS   AGE
backend   2               N/A               1                     2m6s

$ kubectl --context rancher-desktop create --raw "/api/v1/namespaces/flashsale/pods/backend-5b4578d658-88c4z/eviction" -f eviction2.json
{"kind":"Status","apiVersion":"v1","metadata":{},"status":"Success","code":201}
```

Exit code 0。相同的驅逐請求、相同的 PDB，唯一改變的變數（副本數 2→3，餘裕 0→1）把結果從
拒絕翻成接受——這就是 PDB 生效的直接證據：自願性中斷（節點排空、叢集升級這類由人或控制器
發起的驅逐）在低於 `minAvailable` 時會被 API server 擋下，不會靠人工介入。

---

## 7. 量測方法的誠實聲明

### 施壓端在 Windows，不在叢集內

P1 曾經因為 Docker Compose 的 host port 發布層在 300 條瞬間連線下拒絕約 25–30%，而把 k6
放進叢集內執行。P3 重新驗證後發現：**k3s 的 NodePort 有一模一樣的限制**，因為 Compose 與
K8s 的流量都要經過 Rancher Desktop 在 Windows 端的使用者空間中繼行程。把施壓端搬回 Windows
同時解決了兩個問題（時鐘準確、不再誤觸連線層的假失敗），但不是沒有代價，見下面兩節。

### 時鐘：容器內的時間量測不可用

這台機器的 WSL2 VM 時鐘比實際時間快約 3.5%，且牆鐘每約 32.5 秒被 Hyper-V 往回校正一次
（完整診斷見 [量測環境的時鐘準確度](./wsl2-clock-accuracy.md)）。任何在容器內取得的時間長度
都被系統性高估約 3.5%，這正是把施壓端搬到 Windows 的另一半理由——本文件的所有延遲數字都由
k6 在 Windows host 上量測，用的是已驗證準確的 Windows 時鐘，不受這個偏差影響。

### 量測邊界：直接打 backend，沒有 Nginx 也沒有 TLS

`saturation.js` 直接打 `NodePort 30880 -> backend Service -> kube-proxy`，不經過 Nginx
gateway 也不做 TLS 交握。這是刻意的選擇——量測目標是後端本身與 kube-proxy 的負載分配，
不是完整的請求路徑；但也代表本文件的延遲數字**不包含** Nginx 反向代理與 TLS handshake 的
成本，不能直接當成使用者實際感受到的端到端延遲。

### 連線重用政策，以及它為什麼重要

**kube-proxy 是每條 TCP 連線分配一次，不是每個請求分配一次**：iptables 的 DNAT 發生在連線
建立的那一刻，之後同一條 keep-alive 連線上的所有請求都落在同一個 Pod。`saturation.js`
預設重用連線（`connectionReuse: true`，寫進每筆結果檔），這代表：

- 觀察到的「新連線數」（`newConnections`）遠少於總請求數——例如 `replicas=3`、
  `targetRate=900` 這格有 683 條新連線，對應 136492 個 HTTP 請求；連線建立後被重複使用。
- 這是刻意的選擇，不是疏漏：`analyze-saturation.mjs` 會擋下 `newConnections === 0` 的
  執行（代表完全沒有新連線可供觀察，量不到 kube-proxy 的分配），但不要求每個請求都新建連線
  ——那樣做在 Rancher Desktop 的中繼層上會直接撞到下一節的連線數上限。
- 解讀任何跨副本的負載分配數字時，必須同時看 `connectionReuse` 與 `newConnections` 兩個
  欄位；只看請求數會低估實際的連線集中程度。

### Rancher Desktop 中繼層的同時連線上限，以及 `StartRate` 為什麼不能超過 200

Rancher Desktop 在 Windows 端的使用者空間中繼行程對「同時建立中的新連線」有一個實測上限，
約 **210 條**：超過的部分會在 TCP 握手階段被 RST。這個上限卡的是「同時建立中的連線數」，
不是連線速率——把同樣多的連線攤在時間上（例如持續 1,200 條新連線/秒）可以 0 失敗地打進去。
`run-saturation.ps1` 因此把 `StartRate` 硬性限制在 200 以下（超過會直接丟例外拒絕執行）：
`ramping-arrival-rate` 從 `StartRate` 開始爬升，若起跳值本身就逼近或超過 210，會在測試最
一開始就先觸發連線層的假失敗，量到的不是系統行為，而是這一層中繼行程的極限。

### 調高的 `max_connections`，以及哪些數字因此不可比較

Postgres 的 `max_connections` 在本次量測期間從預設 100 調高到 300（原因見第 3 節）。
**這代表本文件的整條 RPS 對 p95 曲線，都是在這個調高後的設定下量出來的。** 需要明確標註
「不可直接比較」的數字：

- **P1 的 `k8s-scale-out-results.json`**（三副本比單副本慢 41% 那組數據）：測量時
  `max_connections` 仍是預設 100，Tomcat 是 `threads.max=400`／`accept-count=300`（P3 量測
  時已改回 Spring Boot 預設 200／100），且用的是封閉模型（`per-vu-iterations`）——三個
  變數都不同，不能拿來跟本文件的曲線做任何形式的直接比較。
- **HPA 實驗與預先擴容對照組的絕對延遲數字**（第 5 節）：兩者都沒有通過
  `analyze-saturation.mjs` 的資料品質關卡，且 `-PreAllocatedVUs`／`-MaxVUs` 用的是
  800/3000（Task 5 乾淨的 `r3-900` 用的是預設 200/600），k6 在 Windows host 上開到接近
  3000 VU 是否反過來壓縮了 Rancher Desktop VM 能拿到的實際運算資源沒有被排除——第 5 節的
  數字只能當方向性證據（HPA 造成的中斷量級遠大於預先擴容），不能拿來跟 Task 5 乾淨的
  `replicas=3`、`targetRate=900` 飽和點（p95 70.9 ms）做「HPA 讓系統變慢了 40 倍」這種
  直接對比——那個 40 倍的落差主要由重啟風暴解釋，但沒有完全排除次要因子（見
  [task-7-report.md](../../.superpowers/sdd/2026-09-13-week8-p3-load-testing-and-autoscaling/task-7-report.md)
  的誠實記錄）。

### 一個觀測工具本身撐不住的教訓：Zipkin

飽和壓測期間，Zipkin 累計重啟 24 次——一個記憶體內的追蹤後端（`512Mi` 限制）在 100% 取樣率
加上這個量級的請求速率下，會被自己要保存的 span 資料撐爆記憶體而 OOM crash-loop。這不影響
本文件任何一個延遲或吞吐數字（Zipkin 不在請求路徑上），但值得記下來：**用來觀測系統的工具，
自己也需要被納入容量規劃**，尤其是全量取樣加高流量的組合。這個問題本身沒有在 P3 範圍內修復。

---

## 相關文件

- 曲線、飽和點、下游瓶頸的完整原始資料：[`k8s-saturation-results.json`](./data/k8s-saturation-results.json)
- P1 的封閉模型結果（不可直接比較，見第 7 節）：[`k8s-scale-out-results.json`](./data/k8s-scale-out-results.json)
- 時鐘與連線層的完整診斷：[量測環境的時鐘準確度](./wsl2-clock-accuracy.md)
- 部署與證據狀態總覽：[k3s 單節點基準](./k3s-baseline.md)
