param(
    [scriptblock]$HttpRequest,
    [string]$KubectlCommand = 'kubectl'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$namespace = 'flashsale'
. (Join-Path $PSScriptRoot 'k8s-preflight.ps1')

function Invoke-BaselineKubectl {
    param([string[]]$Arguments, [string]$Operation)
    return Invoke-KubectlChecked -KubectlCommand $KubectlCommand -Arguments (@('--context', 'rancher-desktop') + $Arguments) -Operation $Operation
}

function Invoke-InsecureHttpsGet {
    param([string]$Uri)
    Add-Type -AssemblyName System.Net.Http
    # 不能把 PowerShell scriptblock 當成憑證驗證 callback：.NET 在背景執行緒上呼叫它，
    # 那個執行緒沒有 Runspace，scriptblock 執行不了，握手直接失敗（Windows PowerShell 5.1）。
    # 改用編譯過的靜態方法，並掛在 ServicePointManager 上讓 HttpClientHandler 沿用。
    if (-not ('FlashSaleBaselineCertTrust' -as [type])) {
        Add-Type -TypeDefinition @'
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class FlashSaleBaselineCertTrust {
    public static bool TrustAll(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors) { return true; }
}
'@
    }
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = [System.Delegate]::CreateDelegate(
        [System.Net.Security.RemoteCertificateValidationCallback],
        [FlashSaleBaselineCertTrust].GetMethod('TrustAll'))
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    $handler = New-Object System.Net.Http.HttpClientHandler
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(10)
    try {
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = $content }
    }
    finally { $client.Dispose(); $handler.Dispose() }
}

Assert-KubernetesPreflight -KubectlCommand $KubectlCommand | Out-Null

foreach ($name in @('postgres', 'redis', 'rabbitmq')) {
    Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'rollout', 'status', "statefulset/$name", '--timeout=240s') -Operation "waiting for statefulset/$name" | Out-Null
}
foreach ($name in @('mailpit', 'zipkin', 'backend', 'frontend', 'nginx')) {
    Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'rollout', 'status', "deployment/$name", '--timeout=240s') -Operation "waiting for deployment/$name" | Out-Null
}

# 期望的 Pod 數量由各工作負載宣告的副本數推導，不寫死。backend 會水平擴展，
# 寫死的數字會在擴展的那一刻讓這支驗證腳本變成假警報。
function Get-DesiredReplicas {
    param([string]$Kind, [string]$Name)
    $value = (Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'get', $Kind, $Name, '-o', 'jsonpath={.spec.replicas}') -Operation "reading the desired replica count for $Kind/$Name" | Out-String).Trim()
    if ([String]::IsNullOrWhiteSpace($value)) { throw "$Kind/$Name did not report a desired replica count." }
    return [int]$value
}

$expectedAppPods = 0
foreach ($name in @('postgres', 'redis', 'rabbitmq')) { $expectedAppPods += Get-DesiredReplicas -Kind 'statefulset' -Name $name }
foreach ($name in @('mailpit', 'zipkin', 'backend', 'frontend', 'nginx')) { $expectedAppPods += Get-DesiredReplicas -Kind 'deployment' -Name $name }

$backendDesired = Get-DesiredReplicas -Kind 'deployment' -Name 'backend'
$backendReady = (Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'get', 'deployment', 'backend', '-o', 'jsonpath={.status.readyReplicas}') -Operation 'checking Backend readiness' | Out-String).Trim()
if ($backendReady -ne [string]$backendDesired) { throw "Expected $backendDesired ready Backend Pod(s), got $backendReady" }

# 用 -o json 而不是 jsonpath：jsonpath 需要內嵌雙引號（{","}、@.type=="Ready"），
# PowerShell 把參數交給原生 exe 時會把引號吃掉，kubectl 收到 {,} 直接拒絕解析。
$appPodsJson = (Invoke-BaselineKubectl -Arguments @(
    '-n', $namespace, 'get', 'pods', '-l', 'app', '-o', 'json'
) -Operation 'checking application Pod states' | Out-String) | ConvertFrom-Json
$appPods = @($appPodsJson.items)
if ($appPods.Count -ne $expectedAppPods) { throw "Expected $expectedAppPods application Pods, got $($appPods.Count)" }
$unexpectedPodStates = @($appPods | Where-Object {
    $conditions = if ($_.status.PSObject.Properties['conditions']) { @($_.status.conditions) } else { @() }
    $ready = @($conditions | Where-Object { $_.type -eq 'Ready' })
    ($_.status.phase -ne 'Running') -or ($ready.Count -ne 1) -or ([string]$ready[0].status -ne 'True')
} | ForEach-Object { "$($_.metadata.name),$($_.status.phase)" })
if ($unexpectedPodStates.Count -ne 0) { throw "Expected all $expectedAppPods application Pods to be Running and Ready; unexpected states: $($unexpectedPodStates -join ', ')" }

$allPodsJson = (Invoke-BaselineKubectl -Arguments @(
    '-n', $namespace, 'get', 'pods', '-o', 'json'
) -Operation 'checking container restart counts' | Out-String) | ConvertFrom-Json
$restarts = 0
foreach ($pod in @($allPodsJson.items)) {
    if (-not $pod.status.PSObject.Properties['containerStatuses']) { continue }
    foreach ($containerStatus in @($pod.status.containerStatuses)) { $restarts += [int]$containerStatus.restartCount }
}
if ($restarts -ne 0) { throw "Expected zero container restarts, got $restarts" }

$pvcCount = @(Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'get', 'pvc', '--no-headers') -Operation 'checking persistent volume claims').Count
if ($pvcCount -ne 3) { throw "Expected three PVCs, got $pvcCount" }

if ($null -eq $HttpRequest) { $HttpRequest = ${function:Invoke-InsecureHttpsGet} }
$healthResponse = & $HttpRequest 'https://localhost:8443/actuator/health/readiness'
if ([int]$healthResponse.StatusCode -ne 200) { throw 'Backend readiness is not UP through Nginx' }
$health = $healthResponse.Content | ConvertFrom-Json
if ($health.status -ne 'UP') { throw 'Backend readiness is not UP through Nginx' }
$front = & $HttpRequest 'https://localhost:8443/'
if ([int]$front.StatusCode -ne 200) { throw 'Frontend route did not return 200' }

Write-Host 'PASS: FlashSale single-node baseline'
