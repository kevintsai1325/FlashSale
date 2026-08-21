param(
    [scriptblock]$HttpRequest,
    [string]$KubectlCommand = 'kubectl'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ns = 'flashsale'

function Assert-CommandAvailable {
    param([string]$Name)

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required but was not found on PATH."
    }
}

function Assert-RancherDesktopContext {
    $context = (& $KubectlCommand config current-context | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the current kubectl context.'
    }

    if ($context -ne 'rancher-desktop') {
        throw 'Select the rancher-desktop kubectl context first. No workload verification was attempted.'
    }
}

function Invoke-Kubectl {
    param([string[]]$Arguments)

    $output = & $KubectlCommand @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed: kubectl $($Arguments -join ' ')"
    }

    return $output
}

function Invoke-InsecureHttpsGet {
    param([string]$Uri)

    Add-Type -AssemblyName System.Net.Http
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.ServerCertificateCustomValidationCallback = {
        param($message, $certificate, $chain, $errors)
        return $true
    }
    $client = New-Object System.Net.Http.HttpClient($handler)

    try {
        $response = $client.GetAsync($Uri).GetAwaiter().GetResult()
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Content = $content
        }
    }
    finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

Assert-CommandAvailable -Name $KubectlCommand
Assert-RancherDesktopContext

@('postgres', 'redis', 'rabbitmq') | ForEach-Object {
    Invoke-Kubectl -Arguments @('-n', $ns, 'rollout', 'status', "statefulset/$_", '--timeout=180s') | Out-Null
}
@('mailpit', 'zipkin', 'backend', 'frontend', 'nginx') | ForEach-Object {
    Invoke-Kubectl -Arguments @('-n', $ns, 'rollout', 'status', "deployment/$_", '--timeout=180s') | Out-Null
}

$backendReady = (Invoke-Kubectl -Arguments @('-n', $ns, 'get', 'deployment', 'backend', '-o', 'jsonpath={.status.readyReplicas}') | Out-String).Trim()
if ($backendReady -ne '1') {
    throw "Expected one ready Backend Pod, got $backendReady"
}

$restartOutput = Invoke-Kubectl -Arguments @('-n', $ns, 'get', 'pods', '-o', 'jsonpath={range .items[*]}{.status.containerStatuses[0].restartCount}{"`n"}{end}')
$restarts = [int]($restartOutput | Measure-Object -Sum).Sum
if ($restarts -ne 0) {
    throw "Expected zero container restarts, got $restarts"
}

$pvcCount = @(Invoke-Kubectl -Arguments @('-n', $ns, 'get', 'pvc', '--no-headers')).Count
if ($pvcCount -ne 3) {
    throw "Expected three PVCs, got $pvcCount"
}

if ($null -eq $HttpRequest) {
    $HttpRequest = ${function:Invoke-InsecureHttpsGet}
}

$healthResponse = & $HttpRequest 'https://localhost:8443/actuator/health/readiness'
if ([int]$healthResponse.StatusCode -ne 200) {
    throw 'Backend readiness is not UP through Nginx'
}
$health = $healthResponse.Content | ConvertFrom-Json
if ($health.status -ne 'UP') {
    throw 'Backend readiness is not UP through Nginx'
}

$front = & $HttpRequest 'https://localhost:8443/'
if ([int]$front.StatusCode -ne 200) {
    throw 'Frontend route did not return 200'
}

Write-Host 'PASS: FlashSale single-node baseline'
