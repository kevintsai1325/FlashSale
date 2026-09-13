# Week 8 無人值守工作記錄（2026-09-13）

使用者離線期間自主執行的工作記錄。每個遇到的問題、所做的決策與其理由都記在這裡，供醒來後一次審閱。

分支：`week8-k8s-scale-out`（從 `main` 的 `86dd751` 開出）

---

## 決策

### D1：開新分支而非直接提交 main

`main` 是預設分支，且本次工作涉及設計文件與後續的 manifest 變更，開 `week8-k8s-scale-out` 分支進行。
使用者醒來後可自行決定合併方式。

### D2：還原 `application.yml` 的未提交 Tomcat 改動

**問題**：工作目錄有一個未提交的改動，把 Tomcat `threads.max` 由 400 調為 300、`accept-count` 由 300 調為 100。
這與 commit `3d720c4 perf: 調大 Tomcat/Hikari/outbox/RabbitMQ 容量以應付 300 VU 併發` 的方向相反。

**決策**：還原為 HEAD 的版本（400／300），並把原本的改動保存為
`docs/superpowers/plans/2026-09-13-local-tomcat-tuning.patch`。

**理由**：P1 的核心產出是 `replicas` 1 與 3 的壓測對照，必須能與既有的
`docs/portfolio/data/benchmark-results.json` 比較。帶著一個未提交、未記錄原因的容量調降去量測，
數字將失去可比性。改動沒有被丟棄，隨時可用 `git apply` 還原。

**待使用者確認**：這個改動是實驗後忘了還原，還是有意為之？若是後者，需要知道原因，因為它會改變 P1 的基線。

### D3：一併提交未追蹤的 k3s 設計文件

`docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md` 處於未追蹤狀態，
但對應的 `k8s/` manifests 已經提交。文件未進版控有遺失風險，且新的設計規格會引用它，故一併提交。

### D4：不處理 `.idea/`

`.idea/` 未被 `.gitignore` 忽略，在 `git status` 中持續顯示為未追蹤。這屬於使用者的 IDE 設定偏好
（有些人刻意版控部分 IDE 設定），不在本次工作範圍內，不擅自修改 `.gitignore`。僅在此記錄。

---

## 遇到的問題

### Q1：kubectl 一開始無法連線（已自行解決）

首次檢查時 `kubectl get nodes` 回報 `dial tcp 127.0.0.1:6443: connection refused`，判斷叢集未啟動。
實際情況是 Rancher Desktop 進程已在執行但仍在初始化。稍後重試即成功：

```
NAME    STATUS   ROLES           AGE   VERSION
mocuo   Ready    control-plane   24d   v1.36.3+k3s1
```

**影響**：設計規格中「Kubernetes 叢集目前未啟動」的描述不正確，需修正。

### Q2：docker CLI 指向錯誤的 daemon

`docker context ls` 顯示當前 context 為 `desktop-linux`（Docker Desktop），但 Docker Desktop 未執行，
所有 docker 指令失敗。Rancher Desktop 的容器引擎設定為 `moby`，應使用 `default` context
（`npipe:////./pipe/docker_engine`）。

切換為 `default` 後首次仍回報 `timed out dialing Hyper-V socket`，多次重試後仍然不通。
Rancher Desktop 的 `dockerd` 確實在 `rancher-desktop` WSL distro 內正常執行
（`/var/run/docker.sock` 存在，`docker version` 回報 Server 29.5.3），只有 Windows 端的
named pipe 橋接壞掉。

**決策（D5）**：不嘗試修復 Windows named pipe（需要重啟 Rancher Desktop，會連帶重啟 k3s，
風險與耗時都高），改由 `wsl -d rancher-desktop -e docker` 呼叫。`/mnt/c` 有掛載，建置 context
可用路徑轉換處理。

### Q3：`build-local.ps1` 與實際容器引擎不相容

`scripts/k8s/build-local.ps1` 硬性要求 `nerdctl` 與 containerd 的 `k8s.io` image namespace，
取不到就直接拋錯。但本機 Rancher Desktop 的容器引擎設定為 **moby**，k3s 以 `--docker` 啟動，
節點回報 `containerRuntimeVersion: docker://29.5.3`，containerd socket 不存在，`nerdctl` 失敗：

```
cannot access containerd socket "/run/k3s/containerd/containerd.sock": no such file or directory
```

更麻煩的是 `docs/portfolio/k3s-baseline.md` 明文寫著「此基準需要 containerd 的 `k8s.io`
image namespace；Docker/Moby 不是可替代引擎」——文件與實際環境直接衝突。

**決策（D6）**：不切換 Rancher Desktop 的容器引擎，改為讓建置腳本依**叢集實際回報的 runtime**
選擇建置工具。

**理由**：切換成 containerd 會讓 Rancher Desktop 不再提供 `docker` 與 `docker compose`，而
README 把 Docker Compose 稱為「最短、完整的本機啟動方式」，是使用者的主要開發流程。在使用者
睡覺時破壞其主要工作流程，代價遠高於改一支建置腳本。而且以 k3s 實際回報的 runtime 做判斷，
本來就比假設某個引擎更正確——這個修正在兩種引擎下都成立。

**待使用者確認**：`k3s-baseline.md` 中「Moby 不是可替代引擎」這句話與實測不符，計畫中安排在
P1 收尾時修正。若當初有其他理由堅持 containerd（例如 image digest 可重現性），請告知。

### Q4：Windows 上沒有 Python，manifest 測試無法執行

`scripts/tests/k8s-manifests-test.ps1` 需要 `python` 搭配 `PyYAML==6.0.3`（釘選於
`scripts/tests/requirements-k8s.txt`）來解析 `kubectl kustomize` 的輸出。Windows PATH 上
沒有 `python`。WSL Ubuntu 有 Python 3.12.3，但 PyYAML 是 6.0.1，版本斷言會失敗。

**決策（D7）**：安裝 Python 3.12 並依 `requirements-k8s.txt` 安裝釘選的 PyYAML。

**理由**：這不是新增相依，而是補齊 repository 自己的測試套件早已宣告的前置條件。沒有它，
P1 中兩個 manifest 相關的任務無法驗證。安裝可逆，且讓測試對使用者自然可重現。

### Q5：既有壓測工具與 Docker Compose 深度綁定

`load-tests/benchmark/collect.ps1` 會自行拉起一個獨立的 Docker Compose 專案
（`compose.benchmark.yaml`、獨立 volume 與 port）再施壓，無法直接對 K8s 使用。

**決策（D8）**：P1 不移植 `collect.ps1`，改以 k6 Job 跑在叢集內、直接打 `backend` Service，
結果另存為 `docs/portfolio/data/k8s-scale-out-results.json`。

**理由**：移植整套 benchmark 工具是獨立的工程，範圍遠超 P1。而且 P1 要量的是
**kube-proxy 的負載分配**，壓力來源必須在叢集內；用 `kubectl port-forward` 從外部打會讓所有
流量經過單一 kubectl 代理連線，直接扭曲要量測的對象。沿用既有結果檔的精神（把重跑所需的環境
事實與量測邊界都寫進檔案），但不共用格式。

### Q6：`deploy.ps1` 斷言單一 Pod，多副本時必定失敗

`deploy.ps1` 在 rollout 後檢查 `if ($pods.Count -ne 1) { throw ... }`。這個斷言的本意是確認
跑起來的是本機建置的映像，但寫法把副本數寫死成 1，`replicas: 3` 時必定拋錯。已列為 P1 的
Task 3，改為以 Deployment 宣告的副本數為期望值，並逐一驗證每個 Pod 的映像。

---

## 執行期間發現的問題

### Q7：PowerShell 指令碼的中文註解會吃掉下一行程式碼（重要，且是全 repo 的潛在問題）

改完 `build-local.ps1` 後，執行時出現詭異錯誤：`The variable '$runtime' cannot be retrieved
because it has not been set`，但錯誤回報的行號與檔案實際內容對不上。加診斷輸出後確認：
`$runtime` 明明有值（`docker://29.5.3`），下一個用到它的判斷卻說變數不存在。

**根因**：本機系統 ANSI 代碼頁是 **950（Big5）**。`.ps1` 檔案若沒有 UTF-8 BOM，
Windows PowerShell 5.1 會以 ANSI 解讀。UTF-8 中文字的位元組落在 Big5 的 lead byte 範圍
（0x81–0xFE），解碼時會連同**後面一個位元組**一起吃掉。當中文註解結尾的位元組對齊恰好讓
lead byte 落在換行符前面時，換行就被吞掉，**下一行程式碼被併入註解而整行消失**。

這解釋了全部症狀：被吞掉的正是 `$runtime = ...` 那一行，所以下一行用到它時變數不存在；
行號也因為少了一行而全部往前位移。

**決策（D9）**：為本次修改過、且含非 ASCII 字元的 `.ps1` 檔案加上 UTF-8 BOM
（`build-local.ps1`、`deploy.ps1`、`verify.ps1`）。加上後 PowerShell 正確以 UTF-8 解碼，
行號與內容完全對上。

**待使用者注意**：這不是只有我改的檔案有問題。**repo 裡所有含中文註解、又沒有 BOM 的
`.ps1` 都有同樣的地雷**，只是目前的位元組對齊剛好沒踩到。`deploy.ps1`、`verify.ps1` 原本
就是這個狀態。建議之後把所有 `.ps1` 統一加上 BOM，或在 `.gitattributes` 中規範編碼。
這是一個「今天沒壞，明天改一個字就壞」的問題。

### Q8：Rancher Desktop 的網路整合層損壞

建置時 buildkit 一直回報 `DeadlineExceeded: context deadline exceeded`，無法取得
`eclipse-temurin` 的 metadata。分層診斷後發現兩件事：

1. Windows 端的 docker named pipe 不通（`timed out dialing Hyper-V socket`）。
2. `rancher-desktop` distro 內的 DNS 完全失效：`/etc/resolv.conf` 指向 Rancher Desktop 的
   閘道解析器 `192.168.127.1`，查詢逾時。但用 `1.1.1.1` 或 `8.8.8.8` 查詢**都正常**，
   代表對外網路本身是通的，只有 Rancher Desktop 自己的解析器壞掉。

一個容易誤導的現象：`docker manifest inspect` 從 Windows 執行會成功，讓人以為 registry 連得上。
實際上那是 **CLI 在 Windows 上解析 DNS**，而 buildkit 是在 distro 內解析 —— 兩條不同的路徑。

**決策（D10）**：
- 以 `rdctl shutdown` + `rdctl start` 重啟 Rancher Desktop。這修好了 Windows 端的 named pipe。
- DNS 沒有被重啟修復，因此改寫 distro 的 `/etc/resolv.conf`，把 `1.1.1.1`、`8.8.8.8` 放在
  Rancher Desktop 閘道之前。原檔已備份為 `/etc/resolv.conf.rd-backup`。

**理由**：重啟是針對根因、可逆、且當時 `flashsale` namespace 尚未部署，沒有東西會遺失。
DNS 的修改同樣可逆。

**待使用者注意**：`/etc/resolv.conf` 的修改可能在 Rancher Desktop 下次重啟時被覆寫。若日後
建置又出現 metadata 逾時，先檢查這個檔案。`192.168.127.1` 為何失效沒有繼續追查（可能與 VPN、
防火牆或 Rancher Desktop 的網路模式設定有關），這是留給使用者的線索。

### Q9：`verify.ps1` 同樣硬性要求單一副本（計畫遺漏）

計畫的 Task 3 只點出 `deploy.ps1` 的單一 Pod 斷言。實作時發現 `verify.ps1` 有兩處相同問題：

```powershell
if ($backendReady -ne '1') { throw "Expected one ready Backend Pod, got $backendReady" }
if ($appPods.Count -ne 8) { throw "Expected eight application Pods, got $($appPods.Count)" }
```

**決策（D11）**：`verify.ps1` 改為從各工作負載宣告的 `spec.replicas` 推導期望值
（三個 StatefulSet 加五個 Deployment 的總和），而不是寫死 8。backend 的就緒副本數也改為與其
宣告值比較。這樣擴展到三副本時，期望值自動變成 10，不需要再改腳本。

這比原本的寫法更好：它驗證的是「跑起來的與宣告的一致」，而不是「剛好是八個」。

### Q10：`load-tests/purchase-flow.js` 自 API 變更後就一直是壞的

首次執行端到端購買驗證時，30 個 VU 全部拿到 **400**，沒有任何一筆進入搶購邏輯。
後端日誌指出真正原因是 `Required request body is missing`：`PurchaseController.purchase()`
需要 `PurchaseCreateRequest`（`{ quantity }`）主體，但腳本送的是 `null`。

`git log` 顯示這個 API 變更來自 `d0a8c12 feat: let buyers choose a purchase quantity within their limit`。
`load-tests/benchmark/purchase-load.js` 當時有跟著改（送 `JSON.stringify({ quantity: 1 })`），
**`load-tests/purchase-flow.js` 沒有**。

最值得注意的是它為什麼沒被發現：這支腳本的檢查是
`'purchase-request never raw 5xx': (r) => r.status < 500`。400 不是 5xx，所以這一項**通過**。
整支端到端腳本在完全沒有執行任何搶購邏輯的情況下，看起來像是綠燈。

**決策（D12）**：修正腳本送出正確的主體。修正後實測 30 買家搶 10 件，checks 160/160 全過、
10 筆訂單、20 筆 SOLD_OUT、庫存歸零、無殘留 PENDING。

**待使用者注意**：`status < 500` 這種檢查會讓 4xx 靜默通過。建議日後在關鍵路徑的檢查上
直接斷言預期狀態碼，而不是只排除 5xx。

### Q11：`maxUnavailable: 0` 不等於零中斷

規格的 P1 驗收要求「滾動更新期間壓測不出現 5xx 與連線中斷」。實測**沒有通過**：
在壓測進行中觸發 `rollout restart`，329 個請求中有 **2 個失敗（0.60%）**。

**根因**：Pod 被刪除時，「從 Endpoints 移除」與「收到 SIGTERM」是兩件並行的事，沒有先後保證。
kube-proxy 重新傳播 iptables 規則需要時間，在那之前流量仍會被導向正在關閉的 Pod。
專案既沒有設定 Spring 的 `server.shutdown: graceful`，manifest 也沒有 `preStop` hook。

**決策（D13）**：只在 manifest 層修正，加上 `preStop: sleep 5` 與
`terminationGracePeriodSeconds: 30`，不修改應用程式設定。

**理由**：preStop 直接針對「Endpoints 傳播延遲」這個根因，而且完全不需要重建映像、
不影響 Docker Compose 環境、不需要重跑 backend 測試套件。若之後發現仍有 in-flight 請求
被切斷，再考慮加上 Spring 的優雅關閉設定。

修正後重測：530/530 checks 全過，**失敗率 0%**。此前後對照已寫入
`docs/portfolio/data/k8s-scale-out-results.json`。

### Q12：`portfolio-docs-test.ps1` 在我開始之前就是紅的（非本次造成）

執行作品集文件契約測試時出現一個失敗：

```
README.md: cites a 300-VU number without carrying the ~212 effective buyers caveat
```

我沒有修改過 `README.md` 或 `portfolio-docs-test.ps1`。為了確認不是我造成的，我在
起始 commit `86dd751` 上開了一個臨時 worktree 執行同一支測試，**同樣失敗**。

**決策（D14）**：不修正，僅記錄。這牽涉到作品集對壓測數字的措辭要怎麼標註，屬於使用者對
自己作品的表述方式，不該由我代為決定。

---

## P1 完成狀態與量測結果

### 驗收對照

| 規格的 P1 驗收項目 | 結果 |
|---|---|
| 所有 Pod Running 且 Ready | 通過。10 個 Pod（backend ×3 加其餘 7 個）全部 Ready |
| 經 `https://localhost:8443` 完成完整購買流程 | 通過。30 買家搶 10 件，checks 160/160，無超賣 |
| `replicas` 1 與 3 的壓測對照數據 | 完成，見 `k8s-scale-out-results.json` |
| 各 Pod 的請求分配比例 | 完成，34.9% / 37.4% / 27.7% |
| 單一 Pod 從建立到 Ready 的耗時 | 完成，10 秒 |
| 排程重複執行的實測證據 | 完成，見 `scheduler-duplication-evidence.md` |
| 兩套 k8s 測試通過 | 通過 |
| 三副本下不超賣仍成立 | **服務請求路徑成立；排程路徑不成立**（見下） |

### 三個推翻預設的量測結果

**1. 三副本比單副本慢。** p95 從 274.97ms 變成 388.03ms（慢 41%），吞吐量持平。
原因是這次的壓測是固定併發（100 VU、每 VU 一次迭代），不是逐步加壓到飽和；而且下游的
Postgres 與 Redis 都只有單一實例，並未成為瓶頸。在下游沒飽和的情況下，多開副本只是讓三個
JVM 在同一台機器上競爭 CPU 與記憶體。

這不代表水平擴展沒用，而是代表**這次的壓測設計無法顯示水平擴展的價值**。要量到擴展的好處，
需要改成「固定到達率、逐步加壓到系統飽和」的壓測，觀察吞吐上限而不是固定併發下的延遲。
這件事已寫入結果檔的 `boundaries`。

**2. Pod 只要 10 秒就 Ready，不是設計時假設的 30–60 秒。**
這直接推翻了規格中「CPU-based HPA 對秒殺場景反應過慢」的預設。10 秒的擴容速度對持續數十秒的
尖峰是有機會跟上的。**P3 的設計必須依這個實測數字重寫**，這正是把後期階段的計畫押後到前期
量測之後才寫的理由。

**3. kube-proxy 的分配明顯不均。** 393 個請求分配為 137 / 147 / 109，最大偏離完美均分 12.2%。
這符合 iptables 模式「隨機選取」而非「輪詢」的行為。樣本數越小偏差越明顯。

---

## 計畫自我檢查修正的問題

撰寫 P1 實作計畫後做自我檢查，修正了五處會導致計畫無法執行的錯誤：

1. **測試變數名錯誤**：原本寫 `$documents`，實際是 `$resources`。
2. **StrictMode 下的屬性存取**：測試以 `Set-StrictMode -Version Latest` 執行，直接寫
   `$x.spec.strategy.type` 在屬性不存在時會拋錯而非回傳 `$null`，必須改用檔案既有的
   `Get-PropertyValue` helper 逐層取值。
3. **遺漏前置相依**：Python 缺失（Q4）原本沒被計畫涵蓋，補上 Task 0。
4. **規格驗收項目遺漏**：規格要求「經 `https://localhost:8443` 完成一次完整購買流程」，
   原計畫只跑 `verify.ps1`（僅健康檢查），補上走 Nginx 與 TLS 的端到端購買驗證。
5. **結果檔格式未定義**：原本只寫「把數據寫入 JSON」，沒有給結構。補上完整 schema，
   並明訂沒量到的欄位寫 `null` 並說明原因，不得以推估值填充。

---

## 本次停在哪裡，以及為什麼

### 已完成

- Week 8 設計規格（P1–P6 路線圖）
- P1 實作計畫，並依自我檢查修正五處錯誤
- **P1 全部執行完畢**：k8s 腳本修正、首次實際部署、壓測對照、滾動更新修復、排程缺陷取證、文件更新
- P2 實作計畫（分散式鎖）

### 刻意停在 P2 實作之前

P2 會加入新的執行期相依（Redisson），並修改 `order`、`notification`、`inventory`、`common`
四個模組的正式程式碼。這是第一個會動到後端業務程式碼的階段。

停下來的理由是**還有兩道未經你審閱的閘門**：

1. **設計規格從未經你確認。** 你說「開始吧」是同意我動筆寫規格，寫完後我請你審閱，
   但你接著就去睡了。規格裡有幾個決定（Redisson 而非 advisory lock、Flink 而非 Kafka Streams、
   3+1 的服務切法）是你在抽象層次選的，還沒看過具體長相。
2. **P1 的量測結果已經推翻了規格中的一個假設。** 規格寫「CPU-based HPA 對秒殺場景反應過慢」，
   但實測 Pod 只要 10 秒就 Ready。P3 那一節需要依實測重寫。既然規格已經需要修訂，
   在修訂前繼續往下實作並不明智。

P2 的計畫已經寫好、可直接執行，只等你點頭。

### 需要你決定或確認的事項

| 項目 | 記錄於 | 需要你做什麼 |
|---|---|---|
| `application.yml` 的 Tomcat 容量被調降 | D2 | 那是實驗殘留還是有意的？我已還原並存成 patch |
| `k3s-baseline.md` 曾寫「Moby 不是可替代引擎」 | Q3 / D6 | 當初堅持 containerd 是否有我不知道的理由？我已依實測更正 |
| 全 repo 的 `.ps1` 中文註解編碼地雷 | Q7 / D9 | 是否要統一為所有 `.ps1` 加 BOM |
| `rancher-desktop` distro 的 `/etc/resolv.conf` 被我修改 | Q8 / D10 | Rancher Desktop 重啟後可能被覆寫；`192.168.127.1` 為何失效未追查 |
| `portfolio-docs-test.ps1` 紅燈（非本次造成） | Q12 / D14 | README 的 300-VU 數字要怎麼標註，屬於你對作品的表述 |
| 設計規格與 P2 計畫 | — | 兩份都待你審閱後才進入實作 |

### 本次新增的檔案

```
docs/superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md   設計規格（P1-P6）
docs/superpowers/plans/2026-09-13-week8-p1-k8s-scale-out.md               P1 實作計畫
docs/superpowers/plans/2026-09-13-week8-p2-distributed-lock.md            P2 實作計畫
docs/superpowers/plans/2026-09-13-week8-autonomous-session-log.md         本檔
docs/superpowers/plans/2026-09-13-local-tomcat-tuning.patch               你未提交改動的備份
docs/portfolio/scheduler-duplication-evidence.md                          排程重複執行證據
docs/portfolio/data/k8s-scale-out-results.json                            擴展量測結果
load-tests/k8s/k6-job.yaml                                                效能量測 Job
load-tests/k8s/k6-e2e-job.yaml                                            端到端驗證 Job
load-tests/k8s/pod-request-counts.sh                                      每 Pod 請求分配量測
load-tests/k8s/ensure-metrics-admin.sh                                    量測用管理員帳號
```

---

# 第二段無人值守：benchmark 收尾與 P2（2026-09-13 下午）

## benchmark 的三次收集

### Q13：第一次完整收集的資料不能發布 —— 負的延遲

`soak` 的 `completedLatencyMs.min` 是 **−1342 ms**。負延遲物理上不可能。

根因：`completedLatency` 以 `Date.now()` 相減計算，而 `Date.now()` 是**牆鐘不是單調時鐘**。
k6 現在跑在 WSL2 的容器內，VM 的時鐘校正會讓牆鐘往回跳。

**這是「把 k6 搬進容器」引入的新脆弱性** —— 修好了一個失真（host port proxy 拒絕連線），
換來另一個（容器內的時鐘）。兩個都必須處理，不能只修一半就發布。

### Q14：第二次的修法不夠 —— 偵測不到的錯誤比偵測得到的危險

第二次我加了「負值就排除並計數」的防護。表面上資料乾淨了（沒有負值），但
`contention-30x10-5` 露出破綻：`completed max` 只有 42ms（其他四次是 290–800ms），
而且 `req/s` 是 **−51.4**（負的吞吐量），`clockSkew` 計數為 10（30 筆中有 10 筆被排除）。

原因：防護只攔得住**量成負值**的樣本。時鐘往回跳 1489ms 時，真實耗時 2000ms 的樣本會被量成
511ms —— **正值、看起來正常、但錯的**。那一次執行有三分之一樣本跨越了校正點，剩下的也不可信。

### 根因量化

以緊密迴圈取樣 30 秒：

| 環境 | 時鐘回跳次數 | 最大回跳 |
|---|---|---|
| Windows 原生（1.2 億次取樣） | **0** | — |
| WSL VM 容器內（9,100 萬次取樣） | **1** | **−1489 ms** |

WSL2 的 VM 時鐘大約每 30 秒被校正一次。17 分鐘的 benchmark 會遇到 30 幾次。
`performance.now()` 在 k6 2.2.0 不可用（已實測），所以腳本內沒有單調時鐘可直接使用。

**決策（D15）**：改為累加兩個單調來源 —— k6 自己量的 `response.timings.duration`
（Go runtime 的單調時鐘）與我們自己指定的 sleep 間隔，完全不碰 `Date.now()`。
負值在數學上變成不可能，因此上一輪加的 `clockSkew` 偵測與計數整組移除（已成死碼，
留著一個永遠是 0 的欄位只會誤導）。

**誠實的代價**：這個數字不含 VU 被 k6 調度器擱置的空檔，因此略小於真實牆鐘耗時。
這是指標定義的改變，發布時會寫進量測邊界。

**待使用者注意**：這個時鐘問題在 **P4／P6 會更嚴重**。Kafka 的事件時間戳與 Flink 的
watermark 都假設時鐘單調遞增。壓測腳本可以繞過牆鐘，事件時間處理繞不過去。
建議在進 P4 之前處理掉 VM 的時鐘問題。

### Q15：兩台機器的資料不可比較

新舊 benchmark 的環境比對發現 **CPU 不同**：舊資料是 i7-11800H（11 代、16 執行緒），
這台是 i7-14650HX（14 代、24 執行緒）。新數據快 30–50% **主要是硬體換代**，不是量測改動。

差一點就把「快了 43%」寫成我的修正帶來的改善。使用者指示以目前這台為基準機器。

**連帶修正**：我在第一段無人值守時更新 `k3s-baseline.md`，把舊 CPU 型號與這台的執行緒數
寫在同一行，組成了一個不存在的機器規格。已修正並標註兩份文件不是同一台機器。

---

## P2 的決策

### D16：`@Transactional` 從排程移到 repository 方法

`ApiAuditRetentionScheduler.purgeExpiredLogs()` 原本帶 `@Transactional`。把工作改成
`schedulerLock.runIfLocked(..., this::doPurgeExpiredLogs)` 之後，內層是以方法參考呼叫的，
**會繞過 Spring 的代理，寫在外層方法上的 `@Transactional` 不會套用到內層**。
而 `deleteByOccurredAtBefore` 是 `@Modifying` 的 delete，沒有交易會失敗。

決策：把 `@Transactional` 加到 `ApiAuditLogJpaRepository.deleteByOccurredAtBefore` 上。
`@Modifying` 的 delete 本來就該自帶交易，這樣呼叫端怎麼包都安全，而且「取鎖」不會被包在
一個開著的資料庫交易裡。

### D17：各排程的租約長度

| 排程 | 鎖名稱 | 租約 | 理由 |
|---|---|---|---|
| `PaymentTimeoutScheduler` | `expireOverduePayments` | 60s | 叢集實測 30 筆訂單數十毫秒處理完；租約為任務間隔（30s）的兩倍，崩潰後最多晚一輪接手 |
| `NotificationRetryScheduler` | `retryDueNotifications` | 120s | 會實際送出郵件，SMTP 往返較慢 |
| `InventoryReconciliationScheduler` | `reconcileInventory` | 120s | 需掃描所有進行中活動並比對 Redis 與 Postgres |
| `ApiAuditRetentionScheduler` | `purgeExpiredAuditLogs` | 300s | 大量刪除可能耗時；每日一次，租約長不影響 |

不使用 Redisson 的看門狗自動續期：續期會讓「持鎖副本崩潰後多久釋放」變得不可預測，
而這些排程本來就允許晚一輪執行。

### Q16：我的批次改寫腳本弄壞了一個檔案

用 Python 批次把三個排程包上鎖時，腳本以「找第一個 `{`」定位建構子主體，但
`ApiAuditRetentionScheduler` 的建構子參數含有 `@Value("${app.audit.retention-days:30}")`，
那個 `${` 讓定位錯位，產生語法錯誤的程式碼。

逐檔審閱時發現並手動重寫該檔。**教訓**：用字串比對批次改 Java 原始碼時，
註解與字串常值裡的括號會破壞天真的括號比對；改完必須逐檔看過，不能只看腳本回報成功。

### Q17：又踩到 cp950 編碼問題，這次是 PowerShell 寫檔

為了驗證「重現超賣的測試在加鎖前真的會紅」，我用 PowerShell 暫時把 `PaymentTimeoutScheduler`
的鎖繞過：

```powershell
(Get-Content $f -Raw) -replace '...' | Set-Content $f -Encoding utf8
```

結果**整個檔案的中文註解變成亂碼**。`Get-Content -Raw` 在這台機器上以 cp950 解碼一個 UTF-8
檔案，再以 UTF-8 寫回，中文全毀。測試因為編譯失敗而以 exit 255 收場。

這與 Q7 是同一個根源（系統 ANSI 代碼頁 950），但表現形式不同：Q7 是 PowerShell **讀取並執行**
`.ps1` 時吃掉換行，這次是 PowerShell **讀寫檔案內容**時破壞字元。

**決策（D18）**：在這台機器上，**一律不用 PowerShell 讀寫含非 ASCII 的檔案**。改用 Python
並明確指定 `encoding="utf-8"`。已從備份還原，改用 Python 重做。

**待使用者注意**：這個地雷會在任何「用 PowerShell 批次改檔案」的場合重現。專案裡有不少
`.ps1` 腳本，如果哪天要寫一支會改動原始碼或設定檔的 PowerShell 腳本，必須顯式指定編碼
（`[IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)` 與 `WriteAllText` 搭配 UTF8Encoding），
不能用 `Get-Content` / `Set-Content` 的預設行為。

### P2 Task 2 驗證：失敗測試確實抓得到缺陷

把 `PaymentTimeoutScheduler` 的鎖暫時繞過後執行 `PaymentTimeoutSchedulerConcurrencyIT`：

```
java.lang.AssertionError: [庫存被回補的次數必須等於逾時訂單數...]
Expecting actual:
  2
to be less than or equal to:
  1
```

`available_quantity = 2` 而 `total_quantity = 1` —— 叢集上那個「庫存憑空生出」的缺陷，
被縮進一個單一 JVM、兩條執行緒、可在 CI 重跑的測試裡了。

這是 P2 最有價值的產出：**叢集實驗無法在 CI 重跑，但這個測試可以**。日後任何人拿掉鎖，
或改動 `OrderCompensationService` 讓它不再冪等，這個測試就會紅。

驗證手法本身也記一下：暫時繞過鎖 → 確認紅 → 還原 → 確認綠。只看「加了鎖之後測試是綠的」
不足以證明測試有效 —— 一個永遠不會紅的測試等於沒有測試。

### D19：Task 7 的故障模式量測不能用牆鐘

計畫的 Task 7 要量「持鎖副本被強制刪除後，其他副本多久才取得鎖」。原本的直覺做法是記錄
刪除 Pod 的時刻與下一次取得鎖的時刻相減 —— 但**那要跨 Windows 與容器兩個時鐘**，而我們
今天已經證明 VM 的牆鐘每約 30 秒往回跳 1.5 秒（見 Q13–Q15）。1.5 秒的誤差對一個「預期
約等於剩餘租約」的量測是致命的。

決策：改用**只在 Windows 這一側量時間**。做法是從 Windows 以固定間隔輪詢 Redis 的鎖鍵是否
存在（`kubectl exec redis -- redis-cli exists scheduler:xxx`），起訖時間都由 Windows 的
時鐘決定。Windows 的時鐘今天實測 30 秒內 0 次回跳，是可信的。

同理，「租約到期導致雙重執行」那一項不量時間，改為觀察
`scheduler.lock.outcome{outcome="expired"}` 這個計數器有沒有增加 —— 計數不受時鐘影響。

### Q18：`deploy.ps1` 無法重新部署（P1 沒發現的缺陷）

要把含 Redisson 的新映像部署上去時，`deploy.ps1` 失敗：

```
Apply failed with 1 conflict: conflict with "kubectl-client-side-apply"
using apps/v1: .spec.volumeClaimTemplates
```

根因：`deploy.ps1` **實際套用時用 client-side apply**（`Apply-Stage` 的 `kubectl apply -k`），
但**驗證 schema 時用 server-side dry-run**。兩者的欄位所有權模型不同，server-side apply 會
因為欄位已被 `kubectl-client-side-apply` 持有而拒絕。

**為什麼 P1 沒發現**：第一次部署時資源還不存在，dry-run 對不存在的資源沒有衝突。
這個缺陷只有在**第二次以後的部署**才會浮現，而 P1 只部署了一次。

**決策（D20）**：在那個 dry-run 加上 `--force-conflicts`。

理由：這一步的目的是「讓 API server 驗證 manifest 結構」，不是接管欄位所有權。它是 dry-run，
`--force-conflicts` 不會寫入任何東西。改動範圍最小，也不需要把整支腳本改成 server-side apply
（那會是更大的變更，且有自己的風險）。

`k8s-scripts-test.ps1` 立刻抓到這個改動（kubectl shim 的引數比對不符），已一併更新 shim 與
對應的順序斷言 —— 測試發揮了它該有的作用。

**順帶清理**：我在 P1 與今天的實驗中用過 `kubectl apply -f -`、`kubectl scale`、
`kubectl set env`，在資源上留下多個 field manager 與過期的
`kubectl.kubernetes.io/last-applied-configuration` annotation。已全部清除。
教訓：**手動 kubectl 操作會污染部署腳本的欄位所有權**，除錯時方便，但事後要清乾淨。

### Q19：Q7 的地雷真的引爆了（同一個檔案，隔一天）

昨晚我記錄 Q7 時寫下：「repo 裡所有含中文註解、又沒有 BOM 的 `.ps1` 都有同樣的地雷，
只是目前的位元組對齊剛好沒踩到。這是一個『今天沒壞，明天改一個字就壞』的問題。」

今天為了加 RBAC 斷言而修改 `k8s-manifests-test.ps1`，位元組對齊改變，**地雷就引爆了**：

```
無法取得變數 '$singleReplicaWorkloads'，因為它尚未設定
於 k8s-manifests-test.ps1:98
```

第 97 行的變數定義被前面兩行中文註解「吃掉」，第 98 行用它時當然找不到。程式碼看起來
完全正常，錯誤訊息也完全不指向真正的原因。

**決策（D21）**：不只修這一個檔案，掃過全 repo 的 `.ps1`，把所有含非 ASCII 但缺 BOM 的
檔案補上 UTF-8 BOM。實際只有這一個檔案（其餘我今天都已經補過了）。

**待使用者注意**：這個問題會在任何人（包括未來的我）編輯這些檔案時重現。建議在
`.gitattributes` 或 CI 加一道檢查：`.ps1` 若含非 ASCII 就必須有 BOM。我沒有擅自加，
因為那會動到 CI 設定。

---

### P2 Task 8：Kubernetes Lease 對照實作

三個設計決定：

1. **不引入 Kubernetes Java client。** Lease 是平凡的 REST 資源，leader election 的邏輯是
   「讀 → 判斷是否過期 → 帶 resourceVersion 寫回」。引入官方 client 連同十幾個傳遞相依會讓
   映像肥一圈，卻把最值得看懂的部分藏進函式庫。改用 JDK 內建的 `HttpClient`，不到一百行。

2. **正確處理 TLS。** API server 用叢集自己的 CA 簽憑證，從 Pod 掛載的 `ca.crt` 建一個只含
   該 CA 的 TrustManager。**沒有用「信任一切」繞過** —— 我們正拿著 ServiceAccount token
   對它說話，被冒充的代價是 token 外洩。

3. **RBAC 收到最小**：只有 `leases`、只有 `get/create/update`、**刻意不給 `delete`**
   （Lease 靠租約過期釋放，給 delete 只會讓出錯的副本有能力抹掉別人的鎖）、Role 而非
   ClusterRole。契約測試已加上斷言保護這四點。

`SchedulerLock` 重構為介面，以 `app.scheduling.lock` 切換兩種實作，預設 `redisson`。

### Q20：Lease 實作的兩個 bug，其中一個的教訓比另一個重要得多

部署 Lease 實作後，排程**完全沒有執行**：`EXPIRED` 訂單 0 筆、叢集裡沒有任何 Lease 物件、
日誌一行錯誤都沒有。

查 `scheduler.lock.outcome` 指標才看出端倪：12 次呼叫，**全部是 `skipped`**。

#### Bug 1（表面）：MicroTime 需要微秒精度

用 backend 的 ServiceAccount 直接打 API，拿到真正的錯誤：

```
parsing time "2026-09-13T10:56:47Z" as "2006-01-02T15:04:05.000000Z07:00":
cannot parse "Z" as ".000000"        HTTP 400 BadRequest
```

Lease 的 `acquireTime` / `renewTime` 是 Kubernetes 的 `MicroTime` 型別，**必須帶六位小數**。
我用的 `DateTimeFormatter.ISO_INSTANT` 在小數為零時會把整個小數部分省略，於是永遠是 400。
改用 `yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'`，實測 HTTP 201。

#### Bug 2（真正的問題）：把硬錯誤當成「別人贏了」

原本的程式碼是：

```java
return response.statusCode() == 201;   // 非 201 一律回 false → 記為 skipped
```

`409 Conflict`（別的副本搶先）與 `400 Bad Request`（請求根本是錯的）被歸為同一類。
結果是**系統安靜地什麼都不做，日誌乾乾淨淨，指標顯示一切「正常跳過」**。

這比 Bug 1 嚴重得多。Bug 1 只要看到錯誤訊息就能在一分鐘內修好；Bug 2 讓錯誤訊息根本不存在。
如果不是我去查指標、再用 SA 手動打 API，這個問題可以隱藏很久 —— 而且表現形式是
「排程莫名其妙不работа」，最難查的那種。

**決策（D22）**：把回應分成三類而不是兩類 —— 成功、409（正常競爭失敗）、其他（拋例外）。
其他狀態一律 `IllegalStateException` 並帶上狀態碼與回應內容，由上層記為 `error` outcome。

**通則**：在分散式協調的程式碼裡，「取不到鎖」與「鎖機制壞掉」必須是兩種可區分的結果。
把它們合併成一個 boolean 會讓系統在故障時表現得像在正常運作。

#### 一個有效的除錯手法

在花時間重建映像之前，先用一個掛著相同 ServiceAccount 的 `curl` Pod 手動打 API 驗證修法。
一輪 build + deploy 要 10 分鐘以上，這個手法把驗證縮到 10 秒，而且直接看到 API server 的
原始錯誤訊息 —— 那是應用程式日誌不會顯示的東西。順帶也驗證了 RBAC 的 `delete` 限制確實
生效（DELETE → HTTP 403）。

### P2 Task 8 完成：對照實測的結果

兩種實作在**正確性上沒有差別**（都是 30 筆訂單全部恰好處理一次），差別在故障行為與運維。

三個實測得到的結論：

1. **接手延遲由「租約 + 排程間隔」決定，不是由鎖的實作決定。** Redisson 量到「鎖鍵消失」
   58.0 秒（租約 57.8 秒），Lease 量到「新副本接手」88.8 秒（租約 60 秒 + 最多 30 秒 tick）。
   **這兩個數字量的不是同一件事** —— Lease 物件不會消失，「接手」是唯一可觀察的事件。
   Redisson 若量同一件事也會落在 58–88 秒。想縮短就得縮租約或加密排程，換機制沒用。

2. **Lease 的過期判定依賴各副本自己的時鐘**，Redisson 則完全由 Redis 單一時鐘決定。
   在本機這種每 30 秒回跳 1.5 秒的環境，這是結構性風險（雖然 1.5/60 = 2.5% 還不足以出事）。
   **這是維持 Redisson 為預設的主要理由。**

3. **運維可見性 Lease 明顯勝出。** `kubectl get leases` 直接顯示持有者是哪個 Pod；
   Redisson 只有 `<UUID>:<threadId>`，對應不回 Pod。這個差異在實驗中造成具體代價：
   測 Redisson 的持有者崩潰時第一次刪錯 Pod，量到的是任務執行時間，差點得出錯誤結論。

**API server 不可用那一項沒有實測**，因為單節點 k3s 上停掉 API server 等同毀掉整個叢集。
文件中已明確標示該段是推論而非量測。

---

## P2 完成狀態

| Task | 狀態 |
|---|---|
| 1. Redisson 相依 | 完成，與 Spring Boot 3.3.4 相容 |
| 2. 重現超賣的失敗測試 | 完成，繞過鎖時紅、加鎖後綠 |
| 3. `SchedulerLock` | 完成，已重構為介面 |
| 4. 四個排程套用 | 完成 |
| 5. 觀測指標 | 完成 |
| 6. 叢集閉環驗證 | 完成：117→60、87→30、每筆恰好一次 |
| 7. 四項故障模式 | 完成（API server 那項標示為推論） |
| 8. K8s Lease 對照 | 完成 |

測試現況：後端 177 個、k8s manifests、k8s scripts、portfolio docs 全數通過。
叢集已切回預設的 Redisson 實作，資料已重置，`verify.ps1` 通過。
