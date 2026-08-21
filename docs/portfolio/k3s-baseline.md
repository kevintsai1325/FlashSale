# Rancher Desktop k3s 單節點基準

這是 FlashSale 在 Windows + Rancher Desktop 上的可重現 Kubernetes lab 操作紀錄。Docker Compose 的[快速開始](../../README.md#快速開始)仍是最短、完整的本機啟動方式，沒有被這份文件取代。

## 證據狀態（截至 2026-08-21）

本節刻意把確認過的環境事實與尚未取得的 live 結果分開；離線 manifest／script 測試不是部署成功的證據。

| 項目 | 狀態 | 證據／說明 |
|---|---|---|
| 筆電 | 已知 | 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz，總記憶體 32 GB。k6 與 k3s 共用同一台實體筆電，因此 CPU、記憶體、磁碟與網路資源會互相影響。 |
| Windows | 已知的先前量測 | Microsoft Windows 11 專業版 10.0.26200；來源是既有 [Compose 壓測環境紀錄](./performance-report.md#量測環境)，不是本次 k3s 部署量測。 |
| `kubectl` 目標 | 已確認 | active context 目前是 `musesaiaks`。只有明確選用 `rancher-desktop` context 時，Rancher Desktop Kubernetes API 的 `/readyz` 探針可達。所有部署、驗證與 teardown 前都必須切到該 context。 |
| RD CPU／RAM 配額、RD／Kubernetes 版本 | 尚未記錄 | 請在真正執行當次從 Rancher Desktop Settings 與下方擷取指令記錄；本文件不臆測數值。 |
| 本機 images | 未完成 | `nerdctl` 目前不在 `PATH`，`flashsale-*:local` 尚未建立或列出。 |
| workloads、路由與 PVC 持久化 | 未完成 | 尚未部署 `flashsale` namespace，未執行 rollout、Nginx 路由或 PostgreSQL PVC 持久化驗證。 |
| 回歸證據 | 已通過離線檢查 | Kubernetes manifest contract 與 k8s scripts 的 offline tests 已通過。Backend 與 frontend suites 也在這次實作前通過；兩者都不是本次重新跑出的 live k3s 證據。 |

完成真正的部署測試後，在本節保留原始非機密輸出，並填寫執行時間、Rancher Desktop CPU／RAM 配置、Rancher Desktop 版本與 Kubernetes 版本：

```powershell
$timestamp = Get-Date -Format o
"Test timestamp: $timestamp"
kubectl version
kubectl get nodes -o wide
kubectl -n flashsale get deployment,statefulset,service,pvc
kubectl -n flashsale top pods
nerdctl --namespace k8s.io images | Select-String 'flashsale-'
```

`kubectl top pods` 需要 Metrics Server；若它失敗，記錄錯誤而不要用猜測的資源數字補上。上述命令不輸出 runtime Secret 值。

## 架構與限制

- This is a single-node, single-machine colocated lab, not a highly available cluster.
- Rancher Desktop Traefik is installed but the baseline request path retains the existing Nginx gateway.
- The baseline uses one Backend Pod; it does not yet validate multi-instance scheduled jobs.
- No number in this document is a production SLA or capacity promise.

`k8s/base` 使用本地 `:local` images 與 `imagePullPolicy: Never`。Nginx 的 `LoadBalancer` Service 仍是入口，HTTPS 路徑為 `https://localhost:8443/`；不會把 image 推到公開 registry，也不以 Traefik 取代現有 Nginx gateway。

## 前置條件與安全的 context 選擇

1. 在 Rancher Desktop 啟用 Kubernetes，並於 **Settings → Container Engine** 選擇 **containerd**，再依 UI 提示套用／重啟。此基準需要 containerd 的 `k8s.io` image namespace；Docker/Moby 不是可替代引擎。
2. 確認 `kubectl` 可用，並在 repository root 執行後續命令。
3. 準備 repository 外的 JWT PEM key pair。私鑰、密碼、產生的 TLS certificate/key 都不可 commit。
4. `build-local.ps1`、`deploy.ps1`、`verify.ps1` 都會檢查 active context，因此單獨替某個命令加 `--context` 不足以通過 guard。

以下命令保存原 context，明確切到 Rancher Desktop，並在任何資源變更前確認 API：

```powershell
$previousContext = (kubectl config current-context).Trim()
kubectl config get-contexts rancher-desktop
kubectl config use-context rancher-desktop
if ((kubectl config current-context).Trim() -ne 'rancher-desktop') {
    throw 'Rancher Desktop context selection failed; stop before building or deploying.'
}
kubectl get --raw=/readyz --request-timeout=5s
```

目前的 `musesaiaks` context 不可承接這個 lab 的命令。完成後如需回復：

```powershell
kubectl config use-context $previousContext
```

### `nerdctl` 不在 PATH 的處理

先確認 Rancher Desktop 使用 containerd，再為**目前 PowerShell session** 找到並加入 `nerdctl.exe` 的資料夾。這不永久修改 PATH；若找不到，先修正 Rancher Desktop 的安裝／engine 設定，不能以公開 image push 繞過 `imagePullPolicy: Never`。

```powershell
if (-not (Get-Command nerdctl -ErrorAction SilentlyContinue)) {
    $candidateDirectories = @(
        "$env:USERPROFILE\.rd\bin",
        "$env:LOCALAPPDATA\Programs\Rancher Desktop\resources\resources\win32\bin"
    ) | Where-Object { Test-Path (Join-Path $_ 'nerdctl.exe') }
    $nerdctlDirectory = $candidateDirectories | Select-Object -First 1
    if (-not $nerdctlDirectory) {
        throw 'nerdctl.exe was not found. Enable Rancher Desktop containerd and restart it before continuing.'
    }
    $env:Path = "$nerdctlDirectory;$env:Path"
}
Get-Command nerdctl
nerdctl version
```

## 建置、部署與驗證

從 repository root 開始，先以 Git Bash 產生 `.gitignore` 排除的自簽 TLS certificate/key：

```bash
MSYS_NO_PATHCONV=1 ./nginx/certs/generate-cert.sh
```

下列 helper 將密碼只放進當前 PowerShell process environment。部署腳本直接把值送往 Kubernetes API 產生／更新 Secret，不會將解碼後內容寫到檔案或標準輸出。

```powershell
function Read-SessionSecret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

$env:FL_K3S_POSTGRES_PASSWORD = Read-SessionSecret 'PostgreSQL password'
$env:FL_K3S_RABBITMQ_PASSWORD = Read-SessionSecret 'RabbitMQ password'
$env:FL_K3S_JWT_PRIVATE_KEY_PATH = 'C:\secure-path\jwt-private.pem'
$env:FL_K3S_JWT_PUBLIC_KEY_PATH = 'C:\secure-path\jwt-public.pem'
```

確認 key paths 指向存在且非空的 PEM 檔後，建置三個 images、部署，並在 deployment 後執行驗證：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\build-local.ps1
nerdctl --namespace k8s.io images | Select-String 'flashsale-'

powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\deploy.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\verify.ps1
```

`verify.ps1` 會等待三個 StatefulSet（PostgreSQL、Redis、RabbitMQ）與五個 Deployment（Mailpit、Zipkin、Backend、Frontend、Nginx），檢查一個 Ready Backend Pod、八個 Ready application Pods、零 container restarts、三個 PVC，並從 Nginx 驗證 backend readiness 與 frontend route。成功後可保留非機密狀態：

```powershell
kubectl -n flashsale get pods,svc,pvc -o wide
kubectl -n flashsale get deployment,statefulset,service,pvc
```

### 日誌、重啟與日常重新部署

```powershell
kubectl -n flashsale logs deployment/backend --tail=200
kubectl -n flashsale logs deployment/nginx --tail=200
kubectl -n flashsale logs deployment/backend --follow

kubectl -n flashsale rollout restart deployment/backend
kubectl -n flashsale rollout status deployment/backend --timeout=180s
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\verify.ps1
```

一般 redeploy 是 image 有變時重跑 `build-local.ps1`，再重跑 `deploy.ps1`，**不是**刪除 namespace。若要驗證 PostgreSQL PVC 持久性，先以非敏感測試資料建立並記錄檢查值、重啟 `postgres-0`、等待 StatefulSet ready，再讀回同一值；尚未完成前不得宣稱持久性已驗證。

### 破壞性 teardown（僅在丟棄整個 lab 時）

```powershell
kubectl delete namespace flashsale
```

**警告：**這會刪除整個 `flashsale` namespace，包括 PVC-backed lab data；它不是一般 redeploy 命令，也不應在保留測試資料、量測結果或除錯證據時執行。執行前確認 active context 仍是 `rancher-desktop`，而非 `musesaiaks` 或其他 cluster。

## 可重跑的離線安全檢查

尚未取得 `nerdctl` 或部署 workload 時，仍可驗證 manifest 與 PowerShell script 的離線契約；這些結果不等同 live deployment evidence：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1
git diff --check
```

預期兩個 PowerShell suite 分別輸出 `PASS: Kubernetes manifest contract` 與 `PASS: k8s scripts use deterministic offline shims for guards, builds, deployment, and secret safety.`，而 `git diff --check` 沒有輸出。
