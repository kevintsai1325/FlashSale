<#
.SYNOPSIS
    每秒記錄一次 backend 的期望副本數、就緒副本數與 HPA 觀察到的 CPU 使用率。

.DESCRIPTION
    這是量「HPA 跟不跟得上」的儀器。時間戳取自 Windows，與 k6 的量測區間對得起來。
    輸出 CSV 而不是 JSON：這份資料的用途是畫時間軸，CSV 直接貼進試算表就能看。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputPath,
    [string]$Context = 'rancher-desktop',
    [string]$Namespace = 'flashsale',
    [int]$DurationSeconds = 300
)

New-Item -ItemType Directory -Path (Split-Path -Parent $OutputPath) -Force | Out-Null
'sampledAt,desiredReplicas,readyReplicas,cpuUtilizationPercent' | Set-Content -LiteralPath $OutputPath -Encoding UTF8

$deadline = (Get-Date).AddSeconds($DurationSeconds)
while ((Get-Date) -lt $deadline) {
    $deployment = & kubectl --context $Context -n $Namespace get deployment backend -o 'jsonpath={.spec.replicas} {.status.readyReplicas}' 2>&1
    $hpa = & kubectl --context $Context -n $Namespace get hpa backend -o 'jsonpath={.status.currentMetrics[0].resource.current.averageUtilization}' 2>&1
    $parts = (($deployment | Out-String).Trim()) -split '\s+'
    $desired = ''
    $ready = '0'
    if ($parts.Length -ge 1) { $desired = $parts[0] }
    if ($parts.Length -ge 2) { $ready = $parts[1] }
    $cpu = ($hpa | Out-String).Trim()
    "$((Get-Date).ToUniversalTime().ToString('o')),$desired,$ready,$cpu" | Add-Content -LiteralPath $OutputPath -Encoding UTF8
    Start-Sleep -Seconds 1
}
Write-Host "時間軸寫入：$OutputPath"
