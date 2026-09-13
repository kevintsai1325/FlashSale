<#
.SYNOPSIS
    壓測期間定期取樣下游指標，輸出 JSON Lines。

.DESCRIPTION
    P1 量到「加副本沒有讓吞吐變好」，最可能的解釋是瓶頸在下游而不是 backend 本身。
    要證實或否定它，就必須在加壓的同時看到連線池與資料庫的狀態。

    取樣端刻意放在 Windows：時間戳才與 k6 的量測區間對得起來（VM 內的時鐘快約 3.5%）。

    需要一個具 ADMIN 角色的帳號才能讀 /actuator/metrics，先跑：
        ADMIN_PASSWORD=... ./load-tests/k8s/ensure-metrics-admin.sh

    取樣方式的限制：hikariActive / hikariIdle / hikariPending 是透過 Nginx gateway
    讀 /actuator/metrics/...，而 gateway 會把請求負載平衡到三個 backend Pod 之一，
    所以這三個數字只是「當下隨機一個副本」的瞬時值，不是叢集總和。相對地，
    pgBackends 是直接對 postgres-0 執行 psql 查 pg_stat_activity，是叢集層級的真實總數，
    不受這個取樣方式影響。
    即使如此，這個取樣方式仍然能回答「瓶頸在不在下游」這個問題：只要任何一次取樣看到
    hikariPending > 0（代表至少有一個副本的連線池已經滿到有請求在排隊），
    或 pgBackends 逼近／超過 Postgres 的連線上限，就足以指認下游是瓶頸，
    不需要每個副本都同時取樣到。若要精確拆解「哪個副本、各自的池用量」，
    需要在叢集內常駐一個探針 Pod 分別打各 Pod 的 /actuator/metrics，
    這超出本腳本範圍，刻意不做。

    關於取樣週期：IntervalSeconds 是「兩次取樣之間的睡眠秒數」，不是取樣週期本身。
    每一輪迴圈的實際耗時 = 收集一個樣本的成本（5 次網路呼叫，其中對 postgres-0 的
    kubectl exec 最慢，實測約 1 秒）+ IntervalSeconds 的睡眠，所以 IntervalSeconds=1
    時，實際週期大約是 1.8～2 秒，不是每秒一次。這裡不追求精準的每秒取樣：每筆樣本
    都帶自己的 sampledAt，用來判讀一段約 90 秒穩態壓測期間下游是否飽和已經足夠。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Gateway = 'https://localhost:8443',
    [string]$AdminEmail = 'metrics-admin@example.com',
    [Parameter(Mandatory = $true)][string]$AdminPassword,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$IntervalSeconds = 1,
    [int]$DurationSeconds = 180
)

# 不用 $ErrorActionPreference = 'Stop'：本腳本呼叫原生 kubectl，PowerShell 5.1 在 Stop 模式下
# 會把原生命令的 stderr 每一行包成 ErrorRecord 並中斷，即使結束碼是 0。

[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }

# Windows PowerShell 5.1 跑在 .NET Framework 上，其 SecurityProtocol 預設值不一定包含
# TLS 1.2。對 gateway 的自簽 HTTPS 端點握手會因此失敗，症狀是每個指標都回傳 null，
# 看起來就像「指標名稱打錯」，其實是連線層根本沒建立起來，必須明講協定版本。
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

function Get-AccessToken {
    $body = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Uri "$Gateway/api/auth/login" -Method Post -Body $body -ContentType 'application/json'
    return $response.accessToken
}

function Get-MetricValue {
    param([string]$Token, [string]$Metric, [string]$Statistic = 'VALUE')
    try {
        $response = Invoke-RestMethod -Uri "$Gateway/actuator/metrics/$Metric" -Headers @{ Authorization = "Bearer $Token" }
        $measurement = $response.measurements | Where-Object { $_.statistic -eq $Statistic } | Select-Object -First 1
        if ($null -eq $measurement) {
            # 指標本身存在（HTTP 呼叫成功），但要求的統計量（例如 MAX）不在回傳的 measurements
            # 裡——這在滾動視窗型的 Timer 上會發生（例如視窗內沒有新樣本，MAX 直接不出現，
            # 而不是回傳 0）。這裡曾經悄悄回傳 null、被呼叫端當成「這個時間點沒有負載」，
            # 實際上是完全不同的一種失敗：指標查得到，但問的統計量查不到。必須跟其他失敗路徑
            # 一樣明講出來，否則會被誤讀成一個真正量到的 0（見 P3 review Critical 1）。
            Write-Warning "指標 '$Metric' 存在，但統計量 '$Statistic' 不在回傳結果裡"
            return $null
        }
        return [double]$measurement.value
    } catch {
        # 取樣失敗一律回傳 null，讓迴圈繼續跑（時間軸比任何單一樣本重要），但不能悄悄地
        # 把「token 過期」「指標名稱打錯」「gateway 中斷」都變成看起來一樣的 null——
        # 否則 180 秒壓測中途的一次 gateway 抖動，會被誤讀成「這個時間點下游沒有負載」。
        Write-Warning "取樣指標 '$Metric' 失敗：$($_.Exception.Message)"
        return $null
    }
}

function Get-PostgresBackends {
    $output = & kubectl --context $Context -n $Namespace exec postgres-0 -- psql -U flashsale -d flashsale -t -A -c "SELECT count(*) FROM pg_stat_activity WHERE datname = 'flashsale';" 2>&1
    if ($LASTEXITCODE -ne 0) {
        # 同樣道理：kubectl exec 失敗（Pod 重啟中、連不上 API server 等）不能悄悄變成 null，
        # 否則跟「Postgres 真的回了合法數字」的樣本在輸出檔裡長得一模一樣。
        Write-Warning "取得 pgBackends 失敗（kubectl exec 結束碼 $LASTEXITCODE）：$(($output | Out-String).Trim())"
        return $null
    }
    $text = ($output | Out-String).Trim()
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) { return $parsed }
    # exec 本身成功（結束碼 0），但輸出不是整數——這是不同於指令失敗的另一種失敗模式
    # （例如 psql 印出警告訊息混進輸出），要能跟上面那種區分開來，才好排查。
    Write-Warning "取得 pgBackends 失敗：kubectl exec 成功但輸出無法解析為整數：'$text'"
    return $null
}

$token = Get-AccessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "無法以 $AdminEmail 登入，請先執行 ensure-metrics-admin.sh" }

$deadline = (Get-Date).AddSeconds($DurationSeconds)
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
Write-Host "取樣中：每次取樣間隔 $IntervalSeconds 秒睡眠（實際週期另加收集成本），共 $DurationSeconds 秒 -> $OutputPath"

while ((Get-Date) -lt $deadline) {
    $reservationSeconds = Get-MetricValue -Token $token -Metric 'purchase.reservation.latency' -Statistic 'MAX'
    $reservationMs = $null
    if ($null -ne $reservationSeconds) { $reservationMs = $reservationSeconds * 1000 }
    $sample = [pscustomobject]@{
        sampledAt        = (Get-Date).ToUniversalTime().ToString('o')
        hikariActive     = Get-MetricValue -Token $token -Metric 'hikaricp.connections.active'
        hikariIdle       = Get-MetricValue -Token $token -Metric 'hikaricp.connections.idle'
        hikariPending    = Get-MetricValue -Token $token -Metric 'hikaricp.connections.pending'
        pgBackends       = Get-PostgresBackends
        reservationMaxMs = $reservationMs
    }
    ($sample | ConvertTo-Json -Compress) | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds $IntervalSeconds
}

Write-Host "取樣結束：$OutputPath"
