<#
.SYNOPSIS
    定期記錄 backend 的期望副本數、就緒副本數與 HPA 觀察到的 CPU 使用率。

.DESCRIPTION
    這是量「HPA 跟不跟得上」的儀器。時間戳取自 Windows，與 k6 的量測區間對得起來。
    輸出 CSV 而不是 JSON：這份資料的用途是畫時間軸，CSV 直接貼進試算表就能看。

    已知限制（Task 7 review 後記錄）：即使把 Deployment 與 HPA 合併成一次 kubectl
    呼叫，實測單輪往返仍要 1-2 秒，加上 $SleepSeconds 的 Sleep，實際取樣週期落在
    2-3 秒量級，不是「每秒一筆」。HPA 的 scaleUp.stabilizationWindowSeconds 設 0，
    可能在一次控制迴圈、數秒內就完成整個反應；本工具的取樣間隔跟這個反應時間是
    同一個數量級，只能保證抓到「已經發生過」的狀態，抓不到「剛好發生的那一刻」。
    量測 HPA 真正的反應時間點時，改用 kubectl get events（HPA 的 SuccessfulRescale
    事件）與 Pod 的 status.conditions[Ready].lastTransitionTime，這兩者都是
    Kubernetes 自己記錄的事件時間戳，不受本工具取樣頻率限制，比這份 CSV 準。
    這份 CSV 仍然有用：拿來看整體趨勢、確認擴縮容有沒有發生、以及跟 k6 的時間窗
    對齊，但不要拿它的某一列時間戳當作「事件剛好在這一刻發生」的證據。

.PARAMETER SleepSeconds
    每輪之間的 Sleep 秒數——不是取樣週期本身。實際週期 = 這個值 + 當輪 kubectl
    往返所花的時間（單節點 Rancher Desktop 上實測約再加 1-2 秒）。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$DurationSeconds = 300,
    [int]$SleepSeconds = 1
)

# 把 Deployment 與 HPA 合併成同一次 kubectl 呼叫（同時指定兩個資源），把每輪的
# kubectl 往返次數從兩次降到一次，藉此縮短取樣週期（見上面 .DESCRIPTION 的限制
# 說明——即使如此還是抓不到「當下」，只是比原本兩次呼叫快一些）。
#
# stdout 與 stderr 分開導到暫存檔，不用 2>&1 合併：HPA 還沒套用、或 API 短暫
# 打嗝時，kubectl 只會對「找不到的那個資源」印錯誤到 stderr，stdout 仍然會是
# 找得到的那個資源的合法 JSON（用真的呼叫過的輸出驗證過，見 fix report）。
# 如果把兩者混在一起，錯誤文字就可能污染要解析的 JSON。
function Get-ScalingSample {
    param([string]$Context, [string]$Namespace)

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        Start-Process -FilePath 'kubectl' -ArgumentList @(
            '--context', $Context, '-n', $Namespace, 'get',
            'deployment/backend', 'hpa/backend', '-o', 'json'
        ) -NoNewWindow -Wait `
            -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile | Out-Null
        $stdout = Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue
        $stderr = Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue
    } finally {
        Remove-Item -LiteralPath $stdoutFile, $stderrFile -ErrorAction SilentlyContinue
    }

    $sample = [pscustomobject]@{ Desired = $null; Ready = $null; Cpu = $null; Warning = $null }

    if ([string]::IsNullOrWhiteSpace($stdout)) {
        $sample.Warning = "kubectl 沒有任何 stdout 輸出。stderr：$($stderr.Trim())"
        return $sample
    }

    try {
        $parsed = $stdout | ConvertFrom-Json -ErrorAction Stop
    } catch {
        $preview = $stdout.Substring(0, [Math]::Min(200, $stdout.Length))
        $sample.Warning = "kubectl 輸出不是合法 JSON，本次樣本略過。前 200 字元：$preview"
        return $sample
    }

    $items = @($parsed.items)
    $deployment = $items | Where-Object { $_.kind -eq 'Deployment' } | Select-Object -First 1
    $hpa = $items | Where-Object { $_.kind -eq 'HorizontalPodAutoscaler' } | Select-Object -First 1

    if ($null -ne $deployment) {
        $sample.Desired = $deployment.spec.replicas
        # readyReplicas 是 0 時，Kubernetes 會直接省略這個欄位（不是寫成 0）；
        # 這裡明確補回 0，跟原始行為一致，不要讓它落進下面「不是整數」的錯誤分支。
        if ($deployment.status.PSObject.Properties['readyReplicas']) {
            $sample.Ready = $deployment.status.readyReplicas
        } else {
            $sample.Ready = 0
        }
    }
    if ($null -ne $hpa -and $hpa.status.PSObject.Properties['currentMetrics']) {
        $metric = @($hpa.status.currentMetrics) | Select-Object -First 1
        if ($null -ne $metric -and $metric.PSObject.Properties['resource']) {
            $sample.Cpu = $metric.resource.current.averageUtilization
        }
    }
    # HPA 尚未套用時，"hpa/backend" 這個資源找不到，kubectl 會對它印一行
    # NotFound 到 stderr（同時仍然把 Deployment 的合法 JSON 印到 stdout）。
    # 這是預期狀況（例如實驗還沒開始、或已經還原環境），只警告不中止。
    if (-not [string]::IsNullOrWhiteSpace($stderr)) {
        $sample.Warning = "kubectl 有 stderr 輸出（HPA 不存在時預期會有）：$($stderr.Trim())"
    }
    return $sample
}

# 寫進 CSV 前先驗證是合法整數，絕不讓 kubectl 的原始輸出（含錯誤訊息）直接落地：
# 錯誤文字裡剛好帶逗號會把欄位對齊撞歪，多行錯誤會把一筆記錄拆成好幾筆殘缺的列，
# 兩種情況都會在使用者沒注意到的情況下悄悄污染整份時間軸。驗證失敗就寫空字串，
# 並且用 Write-Warning 明確指出是哪個欄位、原始值是什麼，方便事後追查。
function ConvertTo-IntFieldOrEmpty {
    param($Value, [string]$FieldName)
    if ($null -eq $Value) { return '' }
    $text = [string]$Value
    $parsedInt = 0
    if ([int]::TryParse($text, [ref]$parsedInt)) {
        return [string]$parsedInt
    }
    Write-Warning "欄位 $FieldName 不是合法整數，寫入空值而非原始內容。原始值：$text"
    return ''
}

New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
'sampledAt,desiredReplicas,readyReplicas,cpuUtilizationPercent' | Set-Content -LiteralPath $OutputPath -Encoding UTF8

$deadline = (Get-Date).AddSeconds($DurationSeconds)
while ((Get-Date) -lt $deadline) {
    $sample = Get-ScalingSample -Context $Context -Namespace $Namespace
    if ($null -ne $sample.Warning) { Write-Warning $sample.Warning }

    $desired = ConvertTo-IntFieldOrEmpty -Value $sample.Desired -FieldName 'desiredReplicas'
    $ready = ConvertTo-IntFieldOrEmpty -Value $sample.Ready -FieldName 'readyReplicas'
    $cpu = ConvertTo-IntFieldOrEmpty -Value $sample.Cpu -FieldName 'cpuUtilizationPercent'

    "$((Get-Date).ToUniversalTime().ToString('o')),$desired,$ready,$cpu" | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds $SleepSeconds
}
Write-Host "時間軸寫入：$OutputPath"
