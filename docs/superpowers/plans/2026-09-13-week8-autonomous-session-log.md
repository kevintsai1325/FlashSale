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

切換為 `default` 後首次仍回報 `timed out dialing Hyper-V socket`，判斷為 WSL 後端尚在暖機。
處理過程記錄於下方執行紀錄。
