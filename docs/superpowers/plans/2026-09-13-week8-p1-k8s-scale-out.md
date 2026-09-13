# Week 8 P1：單體上 K8s 與水平擴展 實作計畫

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把現有 FlashSale 單體部署到本機 k3s，量出 `replicas` 1 與 3 的負載平衡對照數據，並取得 4 個排程任務在多副本下重複執行的證據，作為 P2 分散式鎖的問題依據。

**Architecture:** 不修改任何業務程式碼。只改建置腳本使其支援目前的容器引擎、調整 manifest 的副本數與滾動更新策略、放寬 `deploy.ps1` 的單一 Pod 斷言。壓測以 k6 Job 形式跑在叢集內直接打 `backend` Service，讓 kube-proxy 的負載分配成為被量測的對象。

**Tech Stack:** Rancher Desktop k3s v1.36.3、moby(docker) 29.5.3 作為容器執行環境、kustomize、PowerShell 5.1、k6（grafana/k6 容器）、Spring Boot Actuator 指標。

**Spec:** `docs/superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md`

## Global Constraints

- 不修改 `backend/src` 下任何業務程式碼。P1 是部署與量測，不是功能開發。
- 所有 `kubectl` 操作必須明確帶 `--context rancher-desktop`，沿用既有腳本的慣例。
- Secret 值不得寫入任何檔案、日誌或 commit。JWT 金鑰以檔案路徑經環境變數傳入。
- 壓測必須直接打 `backend` Service，不經 Nginx 與 TLS，與既有 `docs/portfolio/performance-report.md` 的量測邊界一致，數字才可比較。
- 不得使用 `kubectl port-forward` 作為壓測流量路徑。它是單一 kubectl 代理連線，會成為瓶頸並扭曲負載分配的量測結果。
- 既有測試必須維持通過：`scripts/tests/k8s-scripts-test.ps1`、`scripts/tests/k8s-manifests-test.ps1`。
- 環境事實（已確認，不需重新驗證）：叢集節點 `mocuo`，`containerRuntimeVersion` 為 `docker://29.5.3`，metrics-server 已安裝，Windows 端 docker named pipe 不通、僅 `wsl -d rancher-desktop -e docker` 可用。

---

### Task 0: 補齊測試前置相依（Python 與釘選的 PyYAML）

`scripts/tests/k8s-manifests-test.ps1` 以 `kubectl kustomize` 算出資源後，透過 stdin 管線交給
`python` 搭配 PyYAML 解析成 JSON。本機 Windows 上**沒有安裝 Python**，該測試目前完全無法執行。
WSL 的 Ubuntu 有 Python 3.12.3，但其 PyYAML 為 6.0.1，而 `scripts/tests/requirements-k8s.txt`
釘選 `PyYAML==6.0.3`，版本斷言會失敗。

Task 2 與 Task 8 都以此測試作為驗證手段，因此必須先補齊。

**Files:**
- 無 repository 檔案變更（僅安裝開發前置工具）

**Interfaces:**
- Consumes: 無
- Produces: `python` 可在 Windows PATH 上執行，且 `python -c "import yaml; print(yaml.__version__)"` 輸出 `6.0.3`。Task 2 與 Task 8 依賴此前提。

- [ ] **Step 1: 安裝 Python**

```powershell
winget install --id Python.Python.3.12 --source winget --accept-package-agreements --accept-source-agreements --silent
```

若 `winget` 不可用或需要提權而失敗，改用替代方案：在 WSL Ubuntu 建立獨立 venv 並安裝釘選版本，
再於執行測試時以 process 範圍的 PATH 提供一個轉呼叫的 `python.bat`。此替代方案不改動系統，
但也不會讓測試對使用者自然可重現，須在工作記錄中明確標註。

- [ ] **Step 2: 確認 Python 在 PATH 上**

```powershell
$env:PATH = [Environment]::GetEnvironmentVariable('PATH', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('PATH', 'User')
python --version
```
Expected: `Python 3.12.x`

- [ ] **Step 3: 安裝釘選的 PyYAML**

```powershell
python -m pip install -r scripts\tests\requirements-k8s.txt
```

- [ ] **Step 4: 驗證版本與測試可執行**

```powershell
python -c "import yaml; print(yaml.__version__)"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1
```
Expected: 輸出 `6.0.3`，且 manifest 測試在**未做任何修改前**即通過（確認基線是綠的）。

---

### Task 1: 讓 build-local.ps1 支援叢集實際使用的容器引擎

`build-local.ps1` 目前硬性要求 `nerdctl` 與 containerd 的 `k8s.io` namespace，但本機 k3s 以 `--docker` 啟動、`containerRuntimeVersion` 為 `docker://29.5.3`，containerd socket 不存在，`nerdctl` 直接失敗。腳本必須依叢集實際使用的執行環境選擇建置工具，而不是假設 containerd。

**Files:**
- Modify: `scripts/k8s/build-local.ps1`
- Test: `scripts/tests/k8s-scripts-test.ps1`

**Interfaces:**
- Consumes: `Assert-KubernetesPreflight`、`Invoke-KubectlChecked`（皆來自 `scripts/k8s/k8s-preflight.ps1`）
- Produces: `Resolve-ImageBuilder` 函式，回傳 `[pscustomobject]@{ Kind; Command }`，`Kind` 為 `'nerdctl'`、`'docker'` 或 `'wsl-docker'` 三者之一。Task 4 依賴此函式能在本機成功解析為 `docker` 或 `wsl-docker`。

- [ ] **Step 1: 在測試中加入 docker shim 與記錄檔**

在 `scripts/tests/k8s-scripts-test.ps1` 的變數宣告區（`$nerdctlLog` 那一行之後）加入：

```powershell
$dockerLog = Join-Path $testRoot 'docker.log'
```

在 `Set-ShimMode` 函式內，與其他 `WriteAllText` 並列加入：

```powershell
    [IO.File]::WriteAllText($dockerLog, '')
```

在 `New-TestShims` 函式中，比照既有 `nerdctl` shim 的寫法新增一個 `docker` shim，讓它把引數寫進 `FL_K3S_TEST_DOCKER_LOG` 並回傳成功。同時讓 `kubectl` shim 支援新的模式 `docker-runtime`：當引數包含 `containerRuntimeVersion` 時輸出 `docker://29.5.3`。

- [ ] **Step 2: 寫失敗測試**

在 `$commonEnvironment` 宣告處加入 `FL_K3S_TEST_DOCKER_LOG = $dockerLog`，然後在既有的 build 測試之後加入：

```powershell
Set-ShimMode 'docker-runtime'
$dockerBuildResult = Invoke-LocalScript $buildScript $commonEnvironment
Assert-True ($dockerBuildResult.ExitCode -eq 0) 'build-local.ps1 must succeed when the cluster runtime is docker.'
$dockerLines = Get-LogLines $dockerLog
Assert-True (@($dockerLines | Where-Object { $_ -match 'flashsale-backend:local' }).Count -eq 1) 'Docker path must build the backend image exactly once.'
Assert-True (@($dockerLines | Where-Object { $_ -match 'flashsale-frontend:local' }).Count -eq 1) 'Docker path must build the frontend image exactly once.'
Assert-True (@($dockerLines | Where-Object { $_ -match 'flashsale-nginx:local' }).Count -eq 1) 'Docker path must build the nginx image exactly once.'
Assert-True (@(Get-LogLines $nerdctlLog).Count -eq 0) 'Docker runtime must not invoke nerdctl.'
```

- [ ] **Step 3: 執行測試確認失敗**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1`
Expected: FAIL，訊息為 `nerdctl is required for the containerd k8s.io image namespace...`

- [ ] **Step 4: 改寫 build-local.ps1**

把 `build-local.ps1` 中 `nerdctl` 的硬性檢查與建置迴圈整段替換為：

```powershell
function Resolve-ImageBuilder {
    # 以叢集實際使用的執行環境為準，而不是假設某個引擎。
    # k3s 可能以 --docker 啟動（runtime 為 docker://）或使用 containerd（containerd://）。
    $runtime = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', 'get', 'nodes', '-o', 'jsonpath={.items[0].status.nodeInfo.containerRuntimeVersion}') -Operation 'reading the cluster container runtime' | Out-String).Trim()

    if ($runtime -like 'containerd://*') {
        $nerdctl = Get-Command nerdctl -ErrorAction SilentlyContinue
        if ($null -eq $nerdctl) { throw 'Cluster runtime is containerd but nerdctl is not on PATH. Put Rancher Desktop nerdctl on PATH.' }
        return [pscustomobject]@{ Kind = 'nerdctl'; Command = $nerdctl.Source }
    }

    if ($runtime -like 'docker://*') {
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if ($null -ne $docker) {
            & $docker.Source info *> $null
            if ($LASTEXITCODE -eq 0) { return [pscustomobject]@{ Kind = 'docker'; Command = $docker.Source } }
        }
        # Windows 端的 named pipe 可能不通（Hyper-V socket timeout）。
        # Rancher Desktop 的 dockerd 實際跑在 rancher-desktop WSL distro 內，改由該處呼叫。
        $wsl = Get-Command wsl -ErrorAction SilentlyContinue
        if ($null -ne $wsl) {
            & $wsl.Source -d rancher-desktop -e docker info *> $null
            if ($LASTEXITCODE -eq 0) { return [pscustomobject]@{ Kind = 'wsl-docker'; Command = $wsl.Source } }
        }
        throw 'Cluster runtime is docker but no reachable docker daemon was found on the Windows named pipe or in the rancher-desktop WSL distro.'
    }

    throw "Unsupported cluster container runtime: $runtime"
}

function ConvertTo-WslPath {
    param([string]$WindowsPath)
    $full = (Resolve-Path -LiteralPath $WindowsPath).Path
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

function Invoke-ImageBuild {
    param([object]$Builder, [string]$Tag, [string]$ContextPath)
    switch ($Builder.Kind) {
        'nerdctl'    { & $Builder.Command --namespace k8s.io build --tag $Tag $ContextPath }
        'docker'     { & $Builder.Command build --tag $Tag $ContextPath }
        'wsl-docker' { & $Builder.Command -d rancher-desktop -e docker build --tag $Tag (ConvertTo-WslPath $ContextPath) }
        default      { throw "Unknown image builder kind: $($Builder.Kind)" }
    }
    if ($LASTEXITCODE -ne 0) { throw "Image build failed: $Tag" }
}

$builder = Resolve-ImageBuilder
Write-Host "Image builder: $($builder.Kind)"

$builds = @(
    @{ Tag = 'flashsale-backend:local'; Path = 'backend' },
    @{ Tag = 'flashsale-frontend:local'; Path = 'frontend' },
    @{ Tag = 'flashsale-nginx:local'; Path = 'nginx' }
)
foreach ($build in $builds) {
    $contextPath = Join-Path $repo $build.Path
    if (-not (Test-Path -LiteralPath (Join-Path $contextPath 'Dockerfile') -PathType Leaf)) { throw "Dockerfile not found for image build: $($build.Tag)" }
    Invoke-ImageBuild -Builder $builder -Tag $build.Tag -ContextPath $contextPath
}

switch ($builder.Kind) {
    'nerdctl'    { $images = & $builder.Command --namespace k8s.io images }
    'docker'     { $images = & $builder.Command images }
    'wsl-docker' { $images = & $builder.Command -d rancher-desktop -e docker images }
}
if ($LASTEXITCODE -ne 0) { throw 'Unable to list images from the selected image builder.' }
$images | Select-String 'flashsale-(backend|frontend|nginx)'
```

- [ ] **Step 5: 執行測試確認通過**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1`
Expected: PASS，且既有的 containerd 路徑測試仍然通過

- [ ] **Step 6: Commit**

```bash
git add scripts/k8s/build-local.ps1 scripts/tests/k8s-scripts-test.ps1
git commit -m "fix: build-local 依叢集實際 runtime 選擇建置工具，支援 moby"
```

---

### Task 2: manifest 加上明確的滾動更新策略

目前 `backend` Deployment 沒有宣告 `strategy`，套用 Kubernetes 預設值（`maxSurge: 25%`、`maxUnavailable: 25%`）。多副本時預設值意味著更新期間可能有 25% 的 Pod 同時不可用。P1 要驗證滾動更新零中斷，必須把策略寫明確。

**Files:**
- Modify: `k8s/base/application.yaml`
- Test: `scripts/tests/k8s-manifests-test.ps1`

**Interfaces:**
- Consumes: 無
- Produces: `backend` Deployment 具備 `spec.strategy.rollingUpdate.maxUnavailable: 0`。Task 6 的滾動更新驗證依賴此設定。

- [ ] **Step 1: 寫失敗測試**

在 `scripts/tests/k8s-manifests-test.ps1` 既有斷言之後加入：

集合變數名是 `$resources`（不是 `$documents`）。且整份測試在 `Set-StrictMode -Version Latest` 下執行，
直接寫 `$x.spec.strategy.type` 在 `strategy` 不存在時會直接拋錯而非回傳 `$null`，必須用檔案既有的
`Get-PropertyValue` helper 逐層安全取值：

```powershell
$backendDeployment = @($resources | Where-Object { $_.kind -eq 'Deployment' -and $_.metadata.name -eq 'backend' })[0]
Assert-True ($null -ne $backendDeployment) 'backend Deployment must exist.'
$backendStrategy = Get-PropertyValue $backendDeployment.spec 'strategy'
Assert-True ((Get-PropertyValue $backendStrategy 'type') -eq 'RollingUpdate') 'backend must declare an explicit RollingUpdate strategy.'
$backendRollingUpdate = Get-PropertyValue $backendStrategy 'rollingUpdate'
Assert-True ([string](Get-PropertyValue $backendRollingUpdate 'maxUnavailable') -eq '0') 'backend rolling update must keep every existing replica available (maxUnavailable 0).'
Assert-True ([string](Get-PropertyValue $backendRollingUpdate 'maxSurge') -eq '1') 'backend rolling update must add at most one surge Pod at a time.'
```

註：此改動只在既有 Deployment 內新增欄位，不新增資源，因此檔案開頭
`Assert-True ($resources.Count -eq 21)` 的資源總數斷言不受影響。

- [ ] **Step 2: 執行測試確認失敗**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1`
Expected: FAIL，`backend must declare an explicit RollingUpdate strategy.`

- [ ] **Step 3: 修改 manifest**

在 `k8s/base/application.yaml` 的 backend Deployment，於 `replicas: 1` 之後、`selector` 之前插入：

```yaml
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxSurge: 1, maxUnavailable: 0}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add k8s/base/application.yaml scripts/tests/k8s-manifests-test.ps1
git commit -m "feat: backend 宣告明確的滾動更新策略，maxUnavailable 0"
```

---

### Task 3: 讓 deploy.ps1 接受多副本

`deploy.ps1` 在 rollout 後斷言每個 Deployment 恰好有 1 個 Pod：

```powershell
if ($pods.Count -ne 1) { throw "Expected exactly one $name Pod after rollout, got $($pods.Count)." }
```

`replicas` 調成 3 時這行必定拋錯。斷言的本意是「確認跑起來的是本機建置的映像」，不是「只能有一個 Pod」。改為以 Deployment 宣告的副本數為期望值，並逐一檢查每個 Pod 的映像。

**Files:**
- Modify: `scripts/k8s/deploy.ps1`
- Test: `scripts/tests/k8s-scripts-test.ps1`

**Interfaces:**
- Consumes: `Invoke-KubectlChecked`
- Produces: 無新函式。行為變更：deploy 在任意副本數下皆可完成。

- [ ] **Step 1: 寫失敗測試**

在 `scripts/tests/k8s-scripts-test.ps1` 中，讓 kubectl shim 新增模式 `multi-replica`：`get pods -l app=backend -o json` 回傳三個 Pod（皆為 `flashsale-backend:local`、皆有非空 `imageID`、皆無 `deletionTimestamp`），`get deployment backend -o jsonpath={.spec.replicas}` 回傳 `3`。然後加入：

```powershell
Set-ShimMode 'multi-replica'
$multiReplicaResult = Invoke-LocalScript $deployScript $commonEnvironment
Assert-True ($multiReplicaResult.ExitCode -eq 0) 'deploy.ps1 must accept a Deployment with more than one replica.'
Assert-True ($multiReplicaResult.Output -notmatch 'Expected exactly one') 'deploy.ps1 must not assert a single Pod.'
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1`
Expected: FAIL，`Expected exactly one backend Pod after rollout, got 3.`

- [ ] **Step 3: 改寫斷言**

把 `deploy.ps1` 結尾的映像驗證迴圈替換為：

```powershell
foreach ($name in @('backend', 'frontend', 'nginx')) {
    $expected = [int](Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'deployment', $name, '-o', 'jsonpath={.spec.replicas}') -Operation "reading the desired replica count for $name" | Out-String).Trim()
    $podJson = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'pods', '-l', "app=$name", '-o', 'json') -Operation "reading $name image identity" | Out-String) | ConvertFrom-Json
    # rollout status 回來時，被取代的舊 Pod 可能還在 Terminating。它已經標記刪除，
    # 不算在「這次 rollout 的結果」裡，否則這個檢查會隨機失敗。
    $pods = @($podJson.items | Where-Object { $null -eq $_.metadata.PSObject.Properties['deletionTimestamp'] })
    if ($pods.Count -ne $expected) { throw "Expected $expected $name Pod(s) after rollout, got $($pods.Count)." }
    foreach ($pod in $pods) {
        $container = $pod.spec.containers[0]
        $status = $pod.status.containerStatuses[0]
        if ($container.image -ne "flashsale-$name`:local" -or [String]::IsNullOrWhiteSpace([string]$status.imageID)) { throw "$name Pod $($pod.metadata.name) did not report the expected local image and a resolved image ID." }
        Write-Host "Activated $name Pod $($pod.metadata.name) image ID: $($status.imageID)"
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-scripts-test.ps1`
Expected: PASS，單副本與三副本兩種情況都通過

- [ ] **Step 5: Commit**

```bash
git add scripts/k8s/deploy.ps1 scripts/tests/k8s-scripts-test.ps1
git commit -m "fix: deploy 以宣告的副本數為期望值，不再硬性要求單一 Pod"
```

---

### Task 4: 首次實際部署（replicas=1）

這是 `k8s/` 第一次真正被套用到叢集。`docs/portfolio/k3s-baseline.md` 記載先前從未執行成功（context 版本 skew、nerdctl 缺失）。

**Files:**
- Create: `C:\SideProject\flashsale-secrets\jwt-private.pem`（repository 之外，不得 commit）
- Create: `C:\SideProject\flashsale-secrets\jwt-public.pem`（repository 之外，不得 commit）

**Interfaces:**
- Consumes: Task 1 的 `Resolve-ImageBuilder`、Task 3 的多副本 deploy
- Produces: 叢集中運作中的 `flashsale` namespace。Task 5 依賴 `deployment/backend` 為 Ready。

- [ ] **Step 1: 產生 repository 外的 JWT 金鑰對**

```powershell
New-Item -ItemType Directory -Force -Path C:\SideProject\flashsale-secrets | Out-Null
wsl -d rancher-desktop -e sh -c "openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /mnt/c/SideProject/flashsale-secrets/jwt-private.pem"
wsl -d rancher-desktop -e sh -c "openssl rsa -pubout -in /mnt/c/SideProject/flashsale-secrets/jwt-private.pem -out /mnt/c/SideProject/flashsale-secrets/jwt-public.pem"
```

- [ ] **Step 2: 確認金鑰已產生且不在 repository 內**

```powershell
Get-ChildItem C:\SideProject\flashsale-secrets | Select-Object Name, Length
```
Expected: 兩個檔案皆存在且 `Length` 大於 0。路徑不在 `C:\SideProject\FlashSale` 之下。

- [ ] **Step 3: 建置映像**

```powershell
$env:PATH = "$env:LOCALAPPDATA\Programs\Rancher Desktop\resources\resources\win32\bin;$env:PATH"
kubectl config use-context rancher-desktop
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\build-local.ps1
```
Expected: 輸出 `Image builder: wsl-docker`（或 `docker`），最後列出三個 `flashsale-*:local` 映像。首次建置 backend 需下載 Gradle 相依，約 5–15 分鐘。

- [ ] **Step 4: 部署**

```powershell
$env:FL_K3S_POSTGRES_PASSWORD = 'flashsale-local-pg'
$env:FL_K3S_RABBITMQ_PASSWORD = 'flashsale-local-mq'
$env:FL_K3S_JWT_PRIVATE_KEY_PATH = 'C:\SideProject\flashsale-secrets\jwt-private.pem'
$env:FL_K3S_JWT_PUBLIC_KEY_PATH = 'C:\SideProject\flashsale-secrets\jwt-public.pem'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\deploy.ps1
```
Expected: 三個 StatefulSet 與五個 Deployment 皆 rollout 成功，並印出每個 Pod 的 image ID。

- [ ] **Step 5: 驗證**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\k8s\verify.ps1
kubectl --context rancher-desktop -n flashsale get pods -o wide
```
Expected: 所有 Pod `Running` 且 Ready，`verify.ps1` 無錯誤結束。

- [ ] **Step 6: 端到端購買流程驗證（走 Nginx 與 TLS）**

規格的 P1 驗收要求「經 `https://localhost:8443` 完成一次完整購買流程」。`verify.ps1` 只做健康檢查，
不涵蓋購買。以既有的端到端腳本補上這一項：

```powershell
Get-Content -Raw load-tests\benchmark\fixtures.sql | kubectl --context rancher-desktop -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale
kubectl --context rancher-desktop -n flashsale delete job k6-e2e --ignore-not-found
kubectl --context rancher-desktop -n flashsale create configmap k6-scripts --from-file=purchase-flow.js=load-tests\purchase-flow.js --dry-run=client -o yaml | kubectl --context rancher-desktop apply -f -
kubectl --context rancher-desktop -n flashsale run k6-e2e --rm -i --restart=Never --image=grafana/k6:latest --overrides='{\"spec\":{\"containers\":[{\"name\":\"k6\",\"image\":\"grafana/k6:latest\",\"args\":[\"run\",\"--insecure-skip-tls-verify\",\"/scripts/purchase-flow.js\"],\"env\":[{\"name\":\"BASE_URL\",\"value\":\"https://nginx:8443\"},{\"name\":\"VUS\",\"value\":\"30\"},{\"name\":\"STOCK\",\"value\":\"10\"}],\"volumeMounts\":[{\"name\":\"scripts\",\"mountPath\":\"/scripts\"}]}],\"volumes\":[{\"name\":\"scripts\",\"configMap\":{\"name\":\"k6-scripts\"}}]}}'
```
Expected: k6 checks 全數通過 — 代表經 Nginx、TLS、backend、Postgres、RabbitMQ 的完整路徑可用，
且沒有超賣。此為走 Nginx 的**功能驗證**，不是效能量測；效能量測一律直接打 backend Service。

- [ ] **Step 7: 記錄環境事實**

把以下輸出保存到工作記錄，供 Task 8 更新 `k3s-baseline.md` 使用：

```powershell
kubectl --context rancher-desktop version
kubectl --context rancher-desktop get nodes -o wide
kubectl --context rancher-desktop -n flashsale get deployment,statefulset,service,pvc
kubectl --context rancher-desktop -n flashsale top pods
```

---

### Task 5: 量測 replicas=1 的基線

壓測必須跑在叢集內。`kubectl port-forward` 是單一代理連線，會成為瓶頸並扭曲負載分配結果，不可使用。

**Files:**
- Create: `load-tests/k8s/k6-job.yaml`
- Create: `load-tests/k8s/README.md`
- Create: `docs/portfolio/data/k8s-scale-out-results.json`

**Interfaces:**
- Consumes: Task 4 部署完成的叢集
- Produces: `load-tests/k8s/k6-job.yaml` 中的 Job `k6-purchase-flow`，以 ConfigMap `k6-scripts` 掛載腳本。Task 6 重複使用同一份 Job。

- [ ] **Step 1: 建立 k6 Job manifest**

Create `load-tests/k8s/k6-job.yaml`：

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: k6-purchase-flow
  namespace: flashsale
spec:
  backoffLimit: 0
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: k6
          image: grafana/k6:latest
          args: ["run", "/scripts/purchase-flow.js"]
          env:
            - {name: BASE_URL, value: "http://backend:8080"}
            - {name: VUS, value: "100"}
            - {name: STOCK, value: "30"}
            - {name: FLASH_SALE_ID, value: "1"}
          volumeMounts:
            - {name: scripts, mountPath: /scripts}
          resources: {requests: {cpu: 500m, memory: 256Mi}, limits: {cpu: "2", memory: 1Gi}}
      volumes:
        - name: scripts
          configMap: {name: k6-scripts}
```

- [ ] **Step 2: 建立腳本 ConfigMap 並確認可解析**

```powershell
kubectl --context rancher-desktop -n flashsale create configmap k6-scripts --from-file=purchase-flow.js=load-tests\purchase-flow.js --dry-run=client -o yaml | kubectl --context rancher-desktop apply -f -
kubectl --context rancher-desktop -n flashsale get configmap k6-scripts -o jsonpath="{.data.purchase-flow\.js}" | Select-Object -First 1
```
Expected: 輸出腳本開頭內容，代表掛載內容正確。

- [ ] **Step 3: 植入測試資料**

`purchase-flow.js` 需要 id 為 `FLASH_SALE_ID` 的搶購活動與對應庫存。以既有 fixtures 植入：

```powershell
$sql = Get-Content -Raw load-tests\benchmark\fixtures.sql
$sql | kubectl --context rancher-desktop -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale
```
Expected: `INSERT` 輸出無錯誤。若回報主鍵衝突，先執行 `TRUNCATE TABLE purchase_requests, order_items, orders, inventory, flash_sales, products, users RESTART IDENTITY CASCADE;` 再重跑。

- [ ] **Step 4: 執行基線壓測**

```powershell
kubectl --context rancher-desktop -n flashsale delete job k6-purchase-flow --ignore-not-found
kubectl --context rancher-desktop apply -f load-tests\k8s\k6-job.yaml
kubectl --context rancher-desktop -n flashsale wait --for=condition=complete job/k6-purchase-flow --timeout=600s
kubectl --context rancher-desktop -n flashsale logs job/k6-purchase-flow > k6-replicas-1.log
```
Expected: Job 完成，日誌含 k6 摘要（`http_req_duration`、`http_reqs`、checks 通過率）。

- [ ] **Step 5: 記錄結果**

從日誌取出 `http_reqs` 的總數與速率、`http_req_duration` 的 avg／p95／p99，以及 checks 的通過率，
寫入 `docs/portfolio/data/k8s-scale-out-results.json`。沿用既有 `benchmark-results.json` 的精神：
把重跑所需的一切環境事實與量測邊界都寫進結果檔，檔案才可被引用。結構如下：

```json
{
  "schemaVersion": 1,
  "environment": {
    "gitSha": "<git rev-parse HEAD>",
    "kubernetesVersion": "v1.36.3+k3s1",
    "containerRuntime": "docker://29.5.3",
    "node": "mocuo",
    "cpuLogicalCores": 24,
    "memoryGb": 31.6,
    "k6Image": "grafana/k6:latest"
  },
  "boundaries": [
    "壓力直接打 backend Service，不經 Nginx 與 TLS",
    "k6 與叢集共用同一台實體機器，資源互相影響",
    "單節點 k3s，非高可用組態"
  ],
  "runs": {
    "replicas1": {
      "timestamp": "<ISO 8601>",
      "replicas": 1,
      "vus": 100,
      "stock": 30,
      "httpReqs": { "count": 0, "rate": 0.0 },
      "httpReqDurationMs": { "avg": 0.0, "p95": 0.0, "p99": 0.0 },
      "httpReqFailedRate": 0.0,
      "checksPassRate": 0.0,
      "podRequestDistribution": {}
    },
    "replicas3": {
      "timestamp": "<ISO 8601>",
      "replicas": 3,
      "vus": 100,
      "stock": 30,
      "podReadySeconds": 0.0,
      "httpReqs": { "count": 0, "rate": 0.0 },
      "httpReqDurationMs": { "avg": 0.0, "p95": 0.0, "p99": 0.0 },
      "httpReqFailedRate": 0.0,
      "checksPassRate": 0.0,
      "podRequestDistribution": {},
      "rollingUpdateFailedRequests": 0
    }
  },
  "invariants": {
    "noOversell": true,
    "noDuplicateOrders": true
  }
}
```

Task 5 只填 `runs.replicas1`；`runs.replicas3` 由 Task 6 填入。所有數值必須來自實際日誌，
沒量到的欄位寫 `null` 並在 `boundaries` 說明原因，不得以推估值填充。

- [ ] **Step 6: Commit**

```bash
git add load-tests/k8s/ docs/portfolio/data/k8s-scale-out-results.json
git commit -m "test: 加入叢集內 k6 Job 與 replicas=1 壓測基線"
```

---

### Task 6: 擴到 replicas=3 並量測負載分配

**Files:**
- Modify: `docs/portfolio/data/k8s-scale-out-results.json`

**Interfaces:**
- Consumes: Task 5 的 k6 Job 與基線數據
- Produces: `replicas3` 區塊與各 Pod 的請求分配比例

- [ ] **Step 1: 擴容並等待就緒，同時量測 Pod 就緒時間**

```powershell
$start = Get-Date
kubectl --context rancher-desktop -n flashsale scale deployment/backend --replicas=3
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=300s
"Pod ready elapsed: $((Get-Date) - $start)"
```
Expected: 三個 Pod 皆 Ready。記錄耗時 — 這個數字決定 P3 的 HPA 是否有意義。

- [ ] **Step 2: 記錄壓測前各 Pod 的請求計數**

```powershell
$pods = kubectl --context rancher-desktop -n flashsale get pods -l app=backend -o jsonpath="{range .items[*]}{.metadata.name}{'\n'}{end}"
foreach ($p in $pods) {
  $v = kubectl --context rancher-desktop -n flashsale exec $p -- sh -c "wget -qO- http://localhost:8080/actuator/metrics/http_server_requests 2>/dev/null || echo UNAVAILABLE"
  "$p : $v"
}
```
若映像沒有 `wget`，改以臨時 Pod 直接打各 Pod IP：

```powershell
$ips = kubectl --context rancher-desktop -n flashsale get pods -l app=backend -o jsonpath="{range .items[*]}{.status.podIP}{'\n'}{end}"
foreach ($ip in $ips) {
  kubectl --context rancher-desktop -n flashsale run curl-probe --rm -i --restart=Never --image=curlimages/curl:latest -- curl -s "http://${ip}:8080/actuator/metrics/http_server_requests"
}
```
Expected: 每個 Pod 回傳 `COUNT` 測量值。若 actuator 未暴露該端點，記錄此事實並改以 `kubectl logs` 中的請求日誌計數作為替代證據。

- [ ] **Step 3: 重置資料並重跑壓測**

```powershell
kubectl --context rancher-desktop -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c "TRUNCATE TABLE purchase_requests, order_items, orders, inventory, flash_sales, products, users RESTART IDENTITY CASCADE;"
Get-Content -Raw load-tests\benchmark\fixtures.sql | kubectl --context rancher-desktop -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale
kubectl --context rancher-desktop -n flashsale delete job k6-purchase-flow --ignore-not-found
kubectl --context rancher-desktop apply -f load-tests\k8s\k6-job.yaml
kubectl --context rancher-desktop -n flashsale wait --for=condition=complete job/k6-purchase-flow --timeout=600s
kubectl --context rancher-desktop -n flashsale logs job/k6-purchase-flow > k6-replicas-3.log
```
Expected: Job 完成，且 checks 仍全數通過（不超賣的不變量在多副本下必須成立）。

- [ ] **Step 4: 量測壓測後各 Pod 的請求計數並計算分配比例**

重複 Step 2 的指令，計算每個 Pod 的增量與佔比。預期分配不會完全均勻，因為 kube-proxy 的 iptables 模式是隨機選取而非輪詢。

- [ ] **Step 5: 驗證滾動更新零中斷**

在 k6 Job 執行期間觸發滾動更新：

```powershell
kubectl --context rancher-desktop -n flashsale rollout restart deployment/backend
kubectl --context rancher-desktop -n flashsale rollout status deployment/backend --timeout=300s
```
Expected: k6 日誌中 `http_req_failed` 為 0。若非 0，記錄實際數值與失敗原因，不要粉飾。

- [ ] **Step 6: 寫入結果並 commit**

把 `replicas3` 的吞吐、p95、p99、各 Pod 分配比例、Pod 就緒耗時、滾動更新期間的失敗率寫入 `docs/portfolio/data/k8s-scale-out-results.json`。

```bash
git add docs/portfolio/data/k8s-scale-out-results.json
git commit -m "test: 記錄 replicas=3 的壓測對照與負載分配數據"
```

---

### Task 7: 取得排程任務重複執行的證據

這是 P1 最重要的產出，也是 P2 分散式鎖的問題依據。必須真實觀察到，不能只是推論。

**Files:**
- Create: `docs/portfolio/scheduler-duplication-evidence.md`

**Interfaces:**
- Consumes: Task 6 的三副本部署
- Produces: 四個排程任務在多副本下重複執行的日誌證據

- [ ] **Step 1: 確認四個排程任務的日誌特徵**

```powershell
kubectl --context rancher-desktop -n flashsale logs -l app=backend --prefix --tail=500 | Select-String -Pattern "Reconcil|PaymentTimeout|NotificationRetry|ApiAuditRetention"
```
Expected: `--prefix` 會在每行前加上 Pod 名稱，可直接看出同一輪排程被幾個 Pod 各執行一次。

- [ ] **Step 2: 針對 InventoryReconciliationScheduler 取證（60 秒一輪，最快觀察到）**

```powershell
kubectl --context rancher-desktop -n flashsale logs -l app=backend --prefix --since=3m | Select-String -Pattern "Reconcil" | Group-Object { ($_ -split '\s+')[0] } | Select-Object Name, Count
```
Expected: 三個不同 Pod 前綴各自出現。若某個排程在觀察窗內沒有輸出日誌，記錄該事實，並於 Step 3 以資料面證據補強。

- [ ] **Step 3: 針對 PaymentTimeoutScheduler 取資料面證據**

這是後果最嚴重的一項：重複回補庫存會破壞不超賣保證。建立一筆會逾時的訂單，觀察庫存是否被回補多次。

```powershell
kubectl --context rancher-desktop -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c "SELECT id, quantity FROM inventory WHERE flash_sale_id = 1;"
```
在逾時排程觸發前後各記錄一次庫存數量，比對回補幅度是否超過應回補的訂單數量。

- [ ] **Step 4: 寫成證據文件**

Create `docs/portfolio/scheduler-duplication-evidence.md`，內容包含：觀察時間、副本數、每個排程任務的實際日誌片段（Pod 名稱前綴保留）、庫存數據前後比對、以及 `OutboxPublisher` 因 `SKIP LOCKED` 而未重複的對照。

- [ ] **Step 5: Commit**

```bash
git add docs/portfolio/scheduler-duplication-evidence.md
git commit -m "docs: 記錄多副本下排程任務重複執行的實測證據"
```

---

### Task 8: 把 replicas=3 定為預設並更新文件

**Files:**
- Modify: `k8s/base/application.yaml`
- Modify: `docs/portfolio/k3s-baseline.md`
- Modify: `docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md`
- Test: `scripts/tests/k8s-manifests-test.ps1`

**Interfaces:**
- Consumes: Task 6 的量測結果
- Produces: `k8s/base` 預設為三副本的 backend

- [ ] **Step 1: 寫失敗測試**

在 `scripts/tests/k8s-manifests-test.ps1` 加入：

```powershell
Assert-True ([int](Get-PropertyValue $backendDeployment.spec 'replicas') -eq 3) 'backend must default to three replicas after the Week 8 P1 scale-out.'
```

（`$backendDeployment` 由 Task 2 的測試碼定義，本斷言接在其後。）

- [ ] **Step 2: 執行測試確認失敗**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1`
Expected: FAIL

- [ ] **Step 3: 修改 manifest**

`k8s/base/application.yaml` 的 backend Deployment 改為 `replicas: 3`。

- [ ] **Step 4: 執行測試確認通過**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\k8s-manifests-test.ps1`
Expected: PASS

- [ ] **Step 5: 更新 k3s-baseline.md 的證據狀態**

把「證據狀態」表中所有「未完成」「已確認但不相容」的列，更新為本次實際執行的結果。移除「`kubectl` client v1.23 不相容」與「`nerdctl` 不在 PATH」兩項過時描述，改記錄實際使用的 moby 引擎與 `docker://` runtime。同時修正「本基準需要 containerd 的 `k8s.io` image namespace；Docker/Moby 不是可替代引擎」這句話 — 它與實測不符。

- [ ] **Step 6: 標註舊設計文件已被取代**

在 `docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md` 開頭加入一行，指向 `2026-09-13-flashsale-k8s-microservices-design.md`，並說明副本數、資源配額、StatefulSet 與分散式鎖方案四處以新規格為準。

- [ ] **Step 7: Commit**

```bash
git add k8s/base/application.yaml scripts/tests/k8s-manifests-test.ps1 docs/portfolio/k3s-baseline.md docs/superpowers/specs/2026-08-21-k3s-rancher-desktop-deployment-design.md
git commit -m "feat: backend 預設三副本，並以實測結果更新 k3s 基準文件"
```

---

## 完成標準

P1 完成的定義是下列全部成立：

1. `k8s/base` 可在本機 k3s 上一次部署成功，所有 Pod Ready。
2. `docs/portfolio/data/k8s-scale-out-results.json` 同時含有 `replicas1` 與 `replicas3` 兩組數據，且兩者的量測邊界一致。
3. 記錄了各 Pod 的請求分配比例，並對「是否均勻」給出基於數據的結論。
4. 記錄了單一 Pod 從建立到 Ready 的耗時。
5. `docs/portfolio/scheduler-duplication-evidence.md` 含有排程任務重複執行的實際日誌與資料證據。
6. `scripts/tests/k8s-scripts-test.ps1` 與 `scripts/tests/k8s-manifests-test.ps1` 全數通過。
7. 不超賣的不變量在三副本下仍然成立（k6 checks 全通過）。
