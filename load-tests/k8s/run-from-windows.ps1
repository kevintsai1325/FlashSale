<#
.SYNOPSIS
    從 Windows 對 k3s 叢集內的 backend 施壓，並把結果與環境資訊寫成一份執行紀錄。

.DESCRIPTION
    為什麼施壓端要放在 Windows：WSL2 VM 的時鐘實測比實際時間快約 3.5%
    （docs/portfolio/wsl2-clock-accuracy.md），所以叢集內的 k6 Job 量到的延遲被系統性高估。
    Windows 主機的時鐘速率已驗證正確。

    代價是流量必須經過 Rancher Desktop 的使用者空間 relay，而那一層有明確的上限：
      - 瞬間「同時」建立新連線：約 225 條，超過就在 TCP 握手階段被 RST
      - 持續的新連線速率：800/秒實測 0 失敗，1500/秒約 49% 被拒

    因此：
      - 用 arrival-rate 模型或會重用連線的腳本（例如 purchase-flow.js）→ 適合從 Windows 跑
      - 刻意製造「N 條連線同時到達」的情境且 N > 200 → 必須用叢集內的 k6 Job
        （load-tests/k8s/k6-job.yaml），並接受 3.5% 的時間偏差

    量測邊界與叢集內的 Job 一致：直接打 backend，不經 Nginx 與 TLS，
    因此 kube-proxy 對 backend Pod 的分配仍然是被量測的對象。

.NOTES
    這個腳本只負責施壓，不會建立測試資料。執行前請先依 load-tests/README.md 的 Step 1
    在資料庫裡建好對應的搶購活動（FLASH_SALE_ID 與 STOCK 要對得上），
    執行後再依 Step 3 的 SQL 檢查不變量（沒有超賣、沒有重複下單、沒有殘留 PENDING）。

.EXAMPLE
    ./load-tests/k8s/run-from-windows.ps1 -Vus 100 -Stock 30
#>
[CmdletBinding()]
param(
    [string]$ScriptPath = 'load-tests/purchase-flow.js',
    [int]$Vus = 100,
    [int]$Stock = 30,
    [string]$FlashSaleId = '1',
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$NodePort = 30880,
    [switch]$KeepService,
    [string]$OutputDirectory
)

# 刻意不使用 $ErrorActionPreference = 'Stop'：這個腳本大量呼叫原生執行檔（kubectl、k6），
# 而 PowerShell 5.1 在 Stop 模式下會把原生命令寫到 stderr 的每一行包成 ErrorRecord 並中斷，
# 即使該命令的結束碼是 0。這裡一律以 $LASTEXITCODE 判斷成敗。

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$manifest = Join-Path $repoRoot 'k8s\loadtest\backend-nodeport.yaml'
$serviceCreated = $false

function Invoke-Kubectl {
    param([string[]]$Arguments)
    $output = & kubectl --context $Context @Arguments 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = ($output | Out-String).Trim() }
}

function Resolve-K6 {
    $command = Get-Command k6 -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $fallback = Join-Path $HOME 'bin\k6.exe'
    if (Test-Path -LiteralPath $fallback) { return $fallback }
    throw 'k6 not found. Install it (winget install Grafana.k6) or put k6.exe on PATH.'
}

function Wait-ForEndpoint {
    param([string]$Url, [int]$TimeoutSeconds = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -TimeoutSec 5 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return $true }
        } catch {
            # 還沒通就繼續等；轉發規則剛建立時連不上是正常的。
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

try {
    $k6 = Resolve-K6
    Write-Host "k6: $k6"

    if ($Vus -gt 200) {
        Write-Warning "Vus=$Vus 超過 relay 實測可承受的同時連線數（約 225）。"
        Write-Warning '若腳本會重用連線或使用 arrival-rate 模型，這個數字沒有問題；'
        Write-Warning '若腳本刻意讓所有 VU 同時建立新連線，請改用叢集內的 k6-job.yaml。'
    }

    $replicas = (Invoke-Kubectl -Arguments @('-n', $Namespace, 'get', 'deployment', 'backend', '-o', 'jsonpath={.spec.replicas}')).Output
    Write-Host "backend replicas: $replicas"

    $apply = Invoke-Kubectl -Arguments @('apply', '-f', $manifest)
    if ($apply.ExitCode -ne 0) { throw "kubectl apply failed: $($apply.Output)" }
    $serviceCreated = $true
    Write-Host $apply.Output

    $baseUrl = "http://localhost:$NodePort"
    if (-not (Wait-ForEndpoint -Url "$baseUrl/api/flash-sales/$FlashSaleId")) {
        throw "NodePort $NodePort 在 90 秒內沒有回應 200。Rancher Desktop 的埠轉發可能還沒建立。"
    }
    Write-Host "endpoint ready: $baseUrl"

    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $OutputDirectory = Join-Path $repoRoot "load-tests\k8s\results\$stamp"
    }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

    $summaryPath = Join-Path $OutputDirectory 'k6-summary.json'
    $logPath = Join-Path $OutputDirectory 'k6.log'
    $metadataPath = Join-Path $OutputDirectory 'run.json'

    $startedAt = (Get-Date).ToUniversalTime().ToString('o')
    & $k6 run --no-color --summary-export $summaryPath `
        --summary-trend-stats 'avg,min,med,p(95),p(99),max' `
        -e "BASE_URL=$baseUrl" -e "VUS=$Vus" -e "STOCK=$Stock" -e "FLASH_SALE_ID=$FlashSaleId" `
        (Join-Path $repoRoot $ScriptPath) 2>&1 | Tee-Object -FilePath $logPath
    $k6ExitCode = $LASTEXITCODE
    $finishedAt = (Get-Date).ToUniversalTime().ToString('o')

    $gitSha = (& git -C $repoRoot rev-parse HEAD 2>$null)
    $gitDirty = ((& git -C $repoRoot status --porcelain 2>$null) | Measure-Object).Count -gt 0

    [pscustomobject]@{
        startedAt       = $startedAt
        finishedAt      = $finishedAt
        loadSource      = 'windows-host'
        clockSource     = 'windows'   # 見 docs/portfolio/wsl2-clock-accuracy.md：容器內的時鐘快約 3.5%
        path            = "windows -> rancher-desktop relay -> NodePort $NodePort -> backend Service -> kube-proxy"
        script          = $ScriptPath
        vus             = $Vus
        stock           = $Stock
        flashSaleId     = $FlashSaleId
        backendReplicas = $replicas
        k6Version       = (& $k6 version)
        k6ExitCode      = $k6ExitCode
        gitSha          = $gitSha
        gitDirty        = $gitDirty
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

    Write-Host ''
    Write-Host "結果寫入：$OutputDirectory"
    if ($k6ExitCode -ne 0) {
        Write-Warning "k6 以結束碼 $k6ExitCode 結束（可能是 threshold 未達標）。"
    }
    exit $k6ExitCode
} finally {
    # NodePort 讓 backend 繞過 Nginx 直接暴露在節點上，壓測結束就收掉，不留在叢集裡。
    if ($serviceCreated -and (-not $KeepService)) {
        $delete = Invoke-Kubectl -Arguments @('delete', '-f', $manifest, '--ignore-not-found')
        Write-Host $delete.Output
    } elseif ($KeepService) {
        Write-Host "-KeepService：backend-loadtest Service 保留中，記得手動刪除。"
    }
}
