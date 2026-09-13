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
