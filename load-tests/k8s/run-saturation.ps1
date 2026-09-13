<#
.SYNOPSIS
    跑一組飽和式壓測：同一個副本數下逐階段提高到達率，每階段產出一份結果檔。

.DESCRIPTION
    流程：設定副本數 -> 等就緒 -> 種資料 -> 清 Redis -> 套 NodePort -> 建管理員 -> 產 token ->
    每個速率各跑一次（同時背景取樣下游指標）-> 收檔 -> 刪 NodePort。

    施壓端在 Windows，量測邊界是 NodePort 直達 backend Service。
    不要把 StartRate 設到 200 以上：那個區間會撞上 Rancher Desktop 中繼層的同時連線上限
    （約 210 條），量到的不是系統行為（見 docs/portfolio/wsl2-clock-accuracy.md）。

    -Rates 用逗號分隔的字串（例如 '150,300,600,900'），不是 [int[]]：Windows PowerShell 5.1
    以 -File 從外部行程（Bash、排程器）呼叫本腳本時，逗號分隔的陣列引數會被當成單一純量，
    用文化特定的千分位規則轉成一個整數（例如 "50,100" -> 50100），而不是兩個元素；反過來，
    在 PowerShell 工作階段內用 & 呼叫、不加引號時，逗號又會先被解析成陣列、再以空白重組成
    字串（"50,100" -> "50 100"）。宣告成字串、自己解析兩種分隔符號，兩種呼叫方式都能得到
    一致、正確的結果。

    務必幫 -Rates 的值加上引號（如上面 .EXAMPLE）：用 & 呼叫時若不加引號，本腳本的
    [CmdletBinding()] 會讓 PowerShell 直接把解析好的陣列物件拿去綁定字串參數，綁不了會
    立刻丟出 ParameterBindingArgumentTransformationException 而中止——這是好事（吵、不會
    誤跑），但只有加引號才能兩種呼叫方式都正常執行到底。

.EXAMPLE
    ./load-tests/k8s/run-saturation.ps1 -Replicas 3 -Rates '150,300,600,900' -AdminPassword 'MetricsAdmin123!'
#>
[CmdletBinding()]
param(
    [int]$Replicas = 3,
    [string]$Rates = '150,300,600,900',
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [string]$BenchPassword = 'SaturationBench123!',
    [int]$Users = 200,
    [int]$StartRate = 50,
    [int]$RampSeconds = 30,
    [int]$HoldSeconds = 60,
    [int]$PreAllocatedVUs = 200,
    [int]$MaxVUs = 600,
    [int]$NodePort = 30880,
    [switch]$SkipScaling,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [string]$OutputDirectory
)

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$baseUrl = "http://localhost:$NodePort"
$serviceApplied = $false

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
    throw 'k6 not found on PATH.'
}

function ConvertTo-RateList {
    # 把 -Rates 的逗號（或已經被 PowerShell 重組成空白分隔）字串拆成一組正整數。
    # 拆分同時接受逗號與空白，理由見 .DESCRIPTION：兩種呼叫方式（-File 外部呼叫 / 工作階段內
    # 用 & 呼叫）分別會把同一個引數變成 "50,100" 或 "50 100"，缺一種都會誤判成單一元素。
    param([string]$Raw)
    $parts = $Raw -split '[,\s]+' | Where-Object { $_ -ne '' }
    if ($parts.Count -eq 0) {
        throw "Rates='$Raw' 解析不出任何速率；請用逗號分隔的正整數列表，例如 '150,300,600,900'。"
    }
    $parsed = @()
    foreach ($part in $parts) {
        $value = 0
        if (-not [int]::TryParse($part, [ref]$value)) {
            throw "Rates 裡的 '$part' 不是整數；請用逗號分隔的正整數列表，例如 '150,300,600,900'。"
        }
        if ($value -le 0) {
            throw "Rates 裡的 $value 不是正整數；到達率必須大於 0。"
        }
        $parsed += $value
    }
    if ($parsed.Count -eq 1 -and $parsed[0] -gt 10000) {
        # 只解析出一個異常大的值，是「逗號分隔陣列被呼叫方式吃成一個數字」的典型特徵
        # （例如原本想傳 50,100，卻被轉成 50100）——這裡直接點名這個已知的坑，
        # 讓下一個人不必重新花一小時除錯才發現。
        throw "Rates='$Raw' 只解析出一個異常大的速率（$($parsed[0])），超過上限 10000。這通常" +
              "代表逗號分隔的列表被呼叫方式併成了一個數字（例如 -Rates 50,100 被吃成 50100）。" +
              "若原意是多個速率，請確認引數真的以逗號分隔；若真的只要測單一速率，請用 10000 以下的值。"
    }
    return $parsed
}

function Resolve-Bash {
    # PATH 上排在前面的 bash 通常是 C:\Windows\System32\bash.exe（WSL 的殼），不是 Git Bash。
    # 這台機器沒有可用的 WSL 散布版，執行它會直接失敗在 execvpe(/bin/bash): No such file or
    # directory，而不是任何看起來與腳本有關的錯誤。ensure-metrics-admin.sh 需要的是 Git Bash，
    # 所以固定指到已知安裝路徑，不倚賴 PATH 上「哪個 bash 排在前面」。
    $gitBash = Join-Path ${env:ProgramFiles} 'Git\bin\bash.exe'
    if (Test-Path -LiteralPath $gitBash) { return $gitBash }
    $command = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    throw 'bash not found (Git Bash expected at Program Files\Git\bin\bash.exe).'
}

try {
    if ($StartRate -ge 200) {
        throw "StartRate=$StartRate 會在起步就產生逼近中繼層上限的同時連線；請用 200 以下的值。"
    }
    $rateValues = ConvertTo-RateList -Raw $Rates
    $k6 = Resolve-K6
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
        $OutputDirectory = Join-Path $repoRoot "load-tests\k8s\results\saturation-$stamp"
    }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

    if (-not $SkipScaling) {
        Write-Host "縮放 backend 至 $Replicas 個副本"
        $scale = Invoke-Kubectl -Arguments @('-n', $Namespace, 'scale', 'deployment/backend', "--replicas=$Replicas")
        if ($scale.ExitCode -ne 0) { throw $scale.Output }
        $wait = Invoke-Kubectl -Arguments @('-n', $Namespace, 'rollout', 'status', 'deployment/backend', '--timeout=240s')
        if ($wait.ExitCode -ne 0) { throw $wait.Output }
    } else {
        Write-Host '-SkipScaling：不動副本數（HPA 實驗時用這個）'
    }

    Write-Host '種入飽和測試資料'
    $fixture = Join-Path $repoRoot 'load-tests\k8s\fixtures-saturation.sql'
    # -Encoding UTF8 是必要的，不是保險：這台機器的系統代碼頁是 950（Big5）。Get-Content -Raw
    # 若不指名編碼，沒有 BOM 的 .sql 會被當成 Big5 解碼，中文註解的位元組錯位後會把批次
    # 結尾的換行吃掉，導致緊接在最後一行註解後面的 TRUNCATE 整句被併入註解、從未送進 psql——
    # 現象是 INSERT 撞到「上一輪殘留資料」的 duplicate key，而不是任何看得出來的語法錯誤。
    # -v ON_ERROR_STOP=1 讓 psql 在任何一句失敗時真的以非零碼結束，這樣下面的 $LASTEXITCODE
    # 檢查才擋得住「種資料其實失敗了，但腳本繼續往下跑」這種情況。
    Get-Content -LiteralPath $fixture -Raw -Encoding UTF8 |
        & kubectl --context $Context -n $Namespace exec -i postgres-0 -- psql -U flashsale -d flashsale -q -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw '種資料失敗' }
    Invoke-Kubectl -Arguments @('-n', $Namespace, 'exec', 'redis-0', '--', 'redis-cli', 'DEL', 'stock:1') | Out-Null

    $apply = Invoke-Kubectl -Arguments @('apply', '-f', (Join-Path $repoRoot 'k8s\loadtest\backend-nodeport.yaml'))
    if ($apply.ExitCode -ne 0) { throw $apply.Output }
    $serviceApplied = $true

    Write-Host '重建 metrics 管理員（種資料時 users 表被清空了）'
    $env:ADMIN_PASSWORD = $AdminPassword
    & (Resolve-Bash) (Join-Path $repoRoot 'load-tests/k8s/ensure-metrics-admin.sh')
    if ($LASTEXITCODE -ne 0) { throw 'ensure-metrics-admin.sh 失敗' }

    $tokensPath = Join-Path $OutputDirectory 'tokens.json'
    Write-Host "預先產生 $Users 個買家 token"
    & $k6 run (Join-Path $repoRoot 'load-tests\benchmark\prepare.js') `
        -e "USERS=$Users" -e 'RUN_ID=saturation' -e "BASE_URL=$baseUrl" `
        -e "BENCH_PASSWORD=$BenchPassword" -e "TOKENS_OUT=$tokensPath"
    if ($LASTEXITCODE -ne 0) { throw 'prepare.js 失敗' }

    foreach ($rate in $rateValues) {
        $runId = "saturation-r$Replicas-$rate"
        Write-Host ''
        Write-Host "=== $runId ==="
        $summaryPath = Join-Path $OutputDirectory "$runId.json"
        $downstreamPath = Join-Path $OutputDirectory "downstream-$runId.jsonl"
        $totalSeconds = $RampSeconds + $HoldSeconds + 10
        $samplerScript = Join-Path $repoRoot 'load-tests\k8s\sample-downstream.ps1'

        $sampler = Start-Job -ScriptBlock {
            param($script, $out, $password, $seconds)
            powershell -NoProfile -ExecutionPolicy Bypass -File $script -OutputPath $out -AdminPassword $password -DurationSeconds $seconds
        } -ArgumentList $samplerScript, $downstreamPath, $AdminPassword, $totalSeconds

        & $k6 run --no-color (Join-Path $repoRoot 'load-tests\k8s\saturation.js') `
            -e "BASE_URL=$baseUrl" -e "RUN_ID=$runId" -e "REPLICAS=$Replicas" `
            -e "TARGET_RATE=$rate" -e "START_RATE=$StartRate" `
            -e "RAMP_SECONDS=$RampSeconds" -e "HOLD_SECONDS=$HoldSeconds" `
            -e "PRE_ALLOCATED_VUS=$PreAllocatedVUs" -e "MAX_VUS=$MaxVUs" `
            -e "TOKENS_FILE=$tokensPath" -e "SUMMARY_OUT=$summaryPath"
        $k6ExitCode = $LASTEXITCODE

        Wait-Job $sampler -Timeout ($totalSeconds + 30) | Out-Null
        Receive-Job $sampler | Out-Null
        Remove-Job $sampler -Force

        if ($k6ExitCode -ne 0) { Write-Warning "$runId 的 k6 結束碼為 $k6ExitCode" }
    }

    [pscustomobject]@{
        finishedAt  = (Get-Date).ToUniversalTime().ToString('o')
        replicas    = $Replicas
        rates       = $rateValues
        users       = $Users
        startRate   = $StartRate
        rampSeconds = $RampSeconds
        holdSeconds = $HoldSeconds
        loadSource  = 'windows-host'
        clockSource = 'windows'
        baseUrl     = $baseUrl
        gitSha      = (& git -C $repoRoot rev-parse HEAD 2>$null)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'run.json') -Encoding UTF8

    Write-Host ''
    Write-Host "結果目錄：$OutputDirectory"
    Write-Host "接著執行：node load-tests/k8s/analyze-saturation.mjs `"$OutputDirectory`""
} finally {
    if ($serviceApplied) {
        Invoke-Kubectl -Arguments @('delete', '-f', (Join-Path $repoRoot 'k8s\loadtest\backend-nodeport.yaml'), '--ignore-not-found') | Out-Null
    }
}
