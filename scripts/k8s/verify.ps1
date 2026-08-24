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
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.ServerCertificateCustomValidationCallback = { param($message, $certificate, $chain, $errors) return $true }
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

$backendReady = (Invoke-BaselineKubectl -Arguments @('-n', $namespace, 'get', 'deployment', 'backend', '-o', 'jsonpath={.status.readyReplicas}') -Operation 'checking Backend readiness' | Out-String).Trim()
if ($backendReady -ne '1') { throw "Expected one ready Backend Pod, got $backendReady" }

$appPodStates = @(Invoke-BaselineKubectl -Arguments @(
    '-n', $namespace, 'get', 'pods', '-l', 'app', '-o',
    'jsonpath={range .items[*]}{.metadata.name}{","}{.status.phase}{","}{range .status.conditions[?(@.type=="Ready")]}{.status}{end}{"\n"}{end}'
) -Operation 'checking application Pod states' | ForEach-Object { $_.ToString().Trim() } | Where-Object { -not [String]::IsNullOrWhiteSpace($_) })
if ($appPodStates.Count -ne 8) { throw "Expected eight application Pods, got $($appPodStates.Count)" }
$unexpectedPodStates = @($appPodStates | Where-Object {
    $fields = $_.Split([char]',')
    ($fields.Count -ne 3) -or [String]::IsNullOrWhiteSpace($fields[0]) -or $fields[1] -ne 'Running' -or $fields[2] -ne 'True'
})
if ($unexpectedPodStates.Count -ne 0) { throw "Expected all eight application Pods to be Running and Ready; unexpected states: $($unexpectedPodStates -join ', ')" }

$restartCounts = @(Invoke-BaselineKubectl -Arguments @(
    '-n', $namespace, 'get', 'pods', '-o',
    'jsonpath={range .items[*]}{.status.containerStatuses[0].restartCount}{"\n"}{end}'
) -Operation 'checking container restart counts' | ForEach-Object { $_.ToString().Trim() } | Where-Object { -not [String]::IsNullOrWhiteSpace($_) } | ForEach-Object { [int]$_ })
$restarts = if ($restartCounts.Count -eq 0) { 0 } else { [int]($restartCounts | Measure-Object -Sum).Sum }
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
