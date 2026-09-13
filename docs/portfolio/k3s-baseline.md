# Rancher Desktop k3s 單節點基準

這是 FlashSale 在 Windows + Rancher Desktop 上的可重現 Kubernetes lab 操作紀錄。Docker Compose 的[快速開始](../../README.md#快速開始)仍是最短、完整的本機啟動方式，沒有被這份文件取代。

## 證據狀態（截至 2026-09-13）

本節刻意把確認過的環境事實與尚未取得的 live 結果分開；離線 manifest／script 測試不是部署成功的證據。

2026-09-13 完成了本文件原本全部標示為「未完成」的項目：映像建置、部署、rollout、路由與 PVC
持久化都已在實機執行過。在此之前，這套 k3s 部署流程從來沒有真正跑起來過。

| 項目 | 狀態 | 證據／說明 |
|---|---|---|
| 筆電 | 已知 | 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz，總記憶體 32 GB，24 邏輯核心。k6 與 k3s 共用同一台實體筆電，因此 CPU、記憶體、磁碟與網路資源會互相影響。 |
| Windows | 已知的先前量測 | Microsoft Windows 11 專業版 10.0.26200；來源是既有 [Compose 壓測環境紀錄](./performance-report.md#量測環境)，不是本次 k3s 部署量測。 |
| `kubectl` 目標 | 已確認 | active context `rancher-desktop`，client v1.36.3、server v1.36.3+k3s1，minor skew 為 0。先前記錄的 `musesaiaks`／client v1.23 已不再是現況。 |
| Kubernetes 與容器執行環境 | 已確認 | 單節點 `mocuo`，v1.36.3+k3s1；`containerRuntimeVersion` 為 `docker://29.5.3`，即 k3s 以 `--docker` 啟動，使用 Rancher Desktop 的 moby daemon。 |
| 本機 images | 已完成 | `flashsale-backend:local`(621MB)、`flashsale-frontend:local`(74.8MB)、`flashsale-nginx:local`(85.5MB) 皆已建置，並由 `deploy.ps1` 逐一驗證其 resolved `imageID`。 |
| workloads、路由與 PVC 持久化 | 已完成 | `flashsale` namespace 全部 10 個 Pod（backend ×3 加其餘 7 個）Running 且 Ready；3 個 PVC（10Gi／5Gi／2Gi）Bound 於 `local-path`；`verify.ps1` 通過；經 `https://localhost:8443` 走完整購買流程，30 買家搶 10 件，checks 160/160 全過、無超賣。 |
| 水平擴展 | 已完成 | backend `replicas` 1 與 3 的壓測對照、kube-proxy 的實際負載分配、Pod 就緒耗時與滾動更新中斷率，見 [scale-out 結果](./data/k8s-scale-out-results.json)。 |
| 多副本排程任務 | **已驗證且發現缺陷** | 三副本下 4 個無互斥保護的 `@Scheduled` 排程會重複執行，`PaymentTimeoutScheduler` 的重複回補直接造成庫存超賣。證據見 [排程重複執行證據](./scheduler-duplication-evidence.md)。 |
| 回歸證據 | 已通過 | `k8s-manifests-test.ps1` 與 `k8s-scripts-test.ps1` 皆通過。Backend 與 frontend 的測試套件不是本次重新執行的結果。 |

完成真正的部署測試後，在本節保留原始非機密輸出，並填寫執行時間、Rancher Desktop CPU／RAM 配置、Rancher Desktop 版本與 Kubernetes 版本：

```powershell
$timestamp = Get-Date -Format o
"Test timestamp: $timestamp"
kubectl --context rancher-desktop version
kubectl --context rancher-desktop get nodes -o wide
kubectl --context rancher-desktop -n flashsale get deployment,statefulset,service,pvc
kubectl --context rancher-desktop -n flashsale top pods
nerdctl --namespace k8s.io images | Select-String 'flashsale-'
```

`kubectl top pods` 需要 Metrics Server；若它失敗，記錄錯誤而不要用猜測的資源數字補上。上述命令不輸出 runtime Secret 值。

## 架構與限制

- This is a single-node, single-machine colocated lab, not a highly available cluster.
- Rancher Desktop Traefik is installed but the baseline request path retains the existing Nginx gateway.
- The baseline now runs three Backend Pods. Multi-instance scheduled jobs **have** been validated,
  and the result was a defect, not a pass: four unguarded `@Scheduled` jobs run once per replica,
  and `PaymentTimeoutScheduler` doing so destroys the no-oversell invariant. See
  [scheduler duplication evidence](./scheduler-duplication-evidence.md). Fixing it is Week 8 P2.
- No number in this document is a production SLA or capacity promise.

`k8s/base` 使用本地 `:local` images 與 `imagePullPolicy: Never`。Nginx 的 `LoadBalancer` Service 仍是入口，HTTPS 路徑為 `https://localhost:8443/`；不會把 image 推到公開 registry，也不以 Traefik 取代現有 Nginx gateway。

這份 baseline 固定主要版本，但部分 upstream images 仍使用可變動的 major/minor tags（例如 `postgres:16-alpine`、`redis:7-alpine`、`openzipkin/zipkin:3`），因此操作流程可重跑不等於 bit-for-bit image 可重現。真正的 live acceptance 必須保存每個 Pod 的 resolved `imageID`；若要長期重現同一套 bytes，後續應把 upstream images 固定到 digest。

## 前置條件與安全的 context 選擇

1. 在 Rancher Desktop 啟用 Kubernetes。**容器引擎 containerd 與 moby 兩者皆可**，不需要特別切換。
   `build-local.ps1` 會讀取叢集回報的 `containerRuntimeVersion` 並據此選擇建置工具：
   `containerd://` 走 `nerdctl --namespace k8s.io`，`docker://` 走 `docker`（Windows 端的 named pipe
   不通時自動改用 `wsl -d rancher-desktop -e docker`，兩者是同一個 daemon）。
   2026-09-13 的實機執行是在 **moby** 引擎下完成的。
   本文件先前寫「Docker/Moby 不是可替代引擎」，該敘述與實測不符，已更正。
2. 安裝 Kubernetes 1.35～1.37 的 `kubectl`（優先使用 1.36），確認 `Get-Command kubectl` 指向該版本，並在 repository root 執行後續命令。v1.23 不可用於 v1.36 server。
3. 準備 repository 外的 JWT PEM key pair。私鑰、密碼、產生的 TLS certificate/key 都不可 commit。
4. 確認有 default StorageClass，且單節點至少能供應三個 RWO PVC（宣告容量合計 17 GiB）；也確認 host 的 TCP 8080、8443 沒有被其他程式佔用。
5. `build-local.ps1`、`deploy.ps1`、`verify.ps1` 共用相同 preflight：command、active context、client/server minor skew、`/readyz` API。版本或 API 不合時會在 build、mutation、workload check 前停止；其餘 kubectl 操作都明確帶 `--context rancher-desktop`。

以下命令保存原 context，明確切到 Rancher Desktop，並在任何資源變更前確認 API：

```powershell
$previousContext = (kubectl config current-context).Trim()
kubectl config get-contexts rancher-desktop
kubectl config use-context rancher-desktop
if ((kubectl config current-context).Trim() -ne 'rancher-desktop') {
    throw 'Rancher Desktop context selection failed; stop before building or deploying.'
}
kubectl --context rancher-desktop version -o json
kubectl --context rancher-desktop get --raw=/readyz --request-timeout=5s
kubectl --context rancher-desktop get storageclass
Get-NetTCPConnection -State Listen -LocalPort 8080,8443 -ErrorAction SilentlyContinue
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

下列 helper 將密碼只放進當前 PowerShell process environment。第一次部署會以記憶體內 JSON 經 stdin 建立 Secret，不把解碼後內容放進 kubectl process arguments、檔案或標準輸出。初始 PostgreSQL／RabbitMQ 密碼必須存進安全的密碼管理器；PVC 與 `flashsale-secrets` 是同一組必須共同保留的狀態。

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

`deploy.ps1` 依 `bootstrap → foundation → dependency → application` 套用：先只建立 Namespace，接著對完整 rendered baseline 做 server-side dry-run；通過後才建立 Config／Secrets、三個 StatefulSet 與 Mailpit／Zipkin並等待 ready；最後才是 Backend／Frontend／Nginx。把完整 dry-run 放在 Namespace bootstrap 後，可讓第一次部署的 namespaced resources 接受 API schema 驗證，而 Config、Secrets 與 workloads 仍受 dry-run gate 保護。套用 application stage 後，腳本會 restart Backend、Frontend、Nginx，等待 rollout，並列出三者解析後的非機密 image ID；因此相同 `:local` tag 的新 image 與更新後的 TLS `subPath` mount 都會生效。

`verify.ps1` 會等待三個 StatefulSet（PostgreSQL、Redis、RabbitMQ）與五個 Deployment（Mailpit、Zipkin、Backend、Frontend、Nginx），檢查一個 Ready Backend Pod、八個 Ready application Pods、零 container restarts、三個 PVC，並以 10 秒 HTTP timeout 從 Nginx 驗證 backend readiness 與 frontend route。成功後可保留非機密狀態：

```powershell
kubectl --context rancher-desktop -n flashsale get pods,svc,pvc -o wide
kubectl --context rancher-desktop -n flashsale get deployment,statefulset,service,pvc
kubectl --context rancher-desktop -n flashsale get pods -l 'app in (backend,frontend,nginx)' -o custom-columns='NAME:.metadata.name,IMAGE:.spec.containers[0].image,IMAGE_ID:.status.containerStatuses[0].imageID'
```

### Stateful credential 保留政策

第一次部署需要 `FL_K3S_POSTGRES_PASSWORD` 與 `FL_K3S_RABBITMQ_PASSWORD`。之後可從 process environment 移除這兩個變數；腳本會在記憶體中重用 cluster Secret 的既有值。若仍提供，值必須與既有 Secret 完全相同。PVC 已存在但 Secret 遺失時，或嘗試替換任一 stateful 密碼時，部署會在任何 apply／restart 前停止。PostgreSQL／RabbitMQ 的協調式 credential rotation 需要同步更新資料服務內部狀態與 Kubernetes Secret，明確不屬於此基準。

JWT key paths 與 TLS certificate/key 每次 deploy 仍是必要輸入；它們可刻意更新，Backend／Nginx rollout 會載入新值。不要以 `kubectl get secret ... -o yaml` 輸出到一般文字檔做備份；使用受控的 secret manager 或加密備份，並讓 Secret 與 PVC 具有一致的保留／還原生命週期。

### 日誌、重啟與日常重新部署

```powershell
kubectl --context rancher-desktop -n flashsale logs deployment/backend --tail=200
kubectl --context rancher-desktop -n flashsale logs deployment/nginx --tail=200
kubectl --context rancher-desktop -n flashsale logs deployment/backend --follow

kubectl --context rancher-desktop -n flashsale rollout restart deployment/backend
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=240s
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\verify.ps1
```

一般 redeploy 是 image 有變時重跑 `build-local.ps1`，再重跑 `deploy.ps1`，**不是**刪除 namespace。deploy 已包含三個 local-image Deployment 的 restart，不必再手動刪 Pod。

PostgreSQL PVC 持久性可用獨立的非敏感 probe table 驗證；這些命令尚未在本環境執行，取得 live 輸出前不得宣稱持久性已驗證：

```powershell
kubectl --context rancher-desktop -n flashsale exec postgres-0 -- psql -U flashsale -d flashsale -c "CREATE TABLE IF NOT EXISTS k3s_persistence_probe (id integer PRIMARY KEY, marker text NOT NULL); INSERT INTO k3s_persistence_probe VALUES (1, 'before-restart') ON CONFLICT (id) DO UPDATE SET marker = EXCLUDED.marker;"
kubectl --context rancher-desktop -n flashsale exec postgres-0 -- psql -U flashsale -d flashsale -c "SELECT id, marker FROM k3s_persistence_probe;"
kubectl --context rancher-desktop -n flashsale delete pod postgres-0
kubectl --context rancher-desktop -n flashsale rollout status statefulset/postgres --timeout=240s
kubectl --context rancher-desktop -n flashsale exec postgres-0 -- psql -U flashsale -d flashsale -c "SELECT id, marker FROM k3s_persistence_probe;"
kubectl --context rancher-desktop -n flashsale exec postgres-0 -- psql -U flashsale -d flashsale -c "DROP TABLE k3s_persistence_probe;"
```

Zipkin 在 k3s baseline 只有 ClusterIP；用 port-forward 做臨時本機檢查，結束時按 Ctrl+C：

```powershell
kubectl --context rancher-desktop -n flashsale port-forward service/zipkin 9411:9411
# 另一個 PowerShell：
Invoke-RestMethod -TimeoutSec 10 'http://localhost:9411/health'
Invoke-RestMethod -TimeoutSec 10 'http://localhost:9411/api/v2/services'
```

### 破壞性 teardown（僅在丟棄整個 lab 時）

```powershell
kubectl --context rancher-desktop delete namespace flashsale
```

**警告：**這會刪除整個 `flashsale` namespace，包括 PVC-backed lab data；它不是一般 redeploy 命令，也不應在保留測試資料、量測結果或除錯證據時執行。執行前確認 active context 仍是 `rancher-desktop`，而非 `musesaiaks` 或其他 cluster。

## 可重跑的離線安全檢查

尚未取得 `nerdctl` 或相容 `kubectl` 時，仍可驗證 manifest 與 PowerShell script 的離線契約。manifest test 需要 Python 3 與 requirements file 固定的 PyYAML；測試會先檢查 import 與精確版本，再只使用 `kubectl kustomize`（不連 API）。先安裝依賴：

```powershell
python -m pip install -r scripts\tests\requirements-k8s.txt
```

兩個 suite 都先在目前的 PowerShell host 執行；若同一台 Windows 也裝有另一個 host（Windows PowerShell 5.1 或 PowerShell 7），script suite 會自動用另一個 host 再跑 build/deploy smoke paths。這些結果不等同 live deployment evidence：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1
# 若目前使用 PowerShell 7，也可直接以同一 host 跑完整 suite：
pwsh -NoProfile -File scripts\tests\k8s-manifests-test.ps1
pwsh -NoProfile -File scripts\tests\k8s-scripts-test.ps1
git diff --check
```

預期兩個 PowerShell suite 分別輸出 `PASS: Kubernetes rendered-resource contract (21 resources, exact stages, 8 workloads, probes, persistence, headless Services, Secret refs, namespace, and local images).` 與 `PASS: k8s scripts enforce shared version-safe preflight, staged deployment, create-once credentials, stdin Secret safety, local rollouts, and deterministic verification.`，而 `git diff --check` 沒有 whitespace error（Windows 的 Git 設定可能另顯示 LF/CRLF conversion warnings）。
