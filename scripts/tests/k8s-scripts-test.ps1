$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$buildScript = Join-Path $repo 'scripts\k8s\build-local.ps1'
$deployScript = Join-Path $repo 'scripts\k8s\deploy.ps1'
$verifyScript = Join-Path $repo 'scripts\k8s\verify.ps1'
$powershell51 = (Get-Command powershell.exe -ErrorAction Stop).Source
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('flashsale-k8s-script-test-' + [Guid]::NewGuid().ToString('N'))
$shimRoot = Join-Path $testRoot 'bin'
$verifyShimRoot = Join-Path $testRoot 'verify-bin'
$kubectlLog = Join-Path $testRoot 'kubectl.log'
$nerdctlLog = Join-Path $testRoot 'nerdctl.log'
$httpLog = Join-Path $testRoot 'http.log'
$privateKeyPath = Join-Path $testRoot 'jwt-private.pem'
$publicKeyPath = Join-Path $testRoot 'jwt-public.pem'
$certificatePath = Join-Path $repo 'nginx\certs\localhost.crt'
$certificateKeyPath = Join-Path $repo 'nginx\certs\localhost.key'
$createdCertificate = $false
$createdCertificateKey = $false

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) { throw $Message }
}

function Get-LogLines {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    return @(Get-Content -LiteralPath $Path)
}

function Invoke-LocalScript {
    param(
        [string]$ScriptPath,
        [hashtable]$Environment = @{}
    )

    $savedValues = @{}
    foreach ($name in $Environment.Keys) {
        $savedValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $Environment[$name], 'Process')
    }

    $savedPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
    [Environment]::SetEnvironmentVariable('PATH', ($shimRoot + ';' + $savedPath), 'Process')

    try {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $output = & $powershell51 -NoProfile -ExecutionPolicy Bypass -File $ScriptPath 2>&1 | Out-String
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }

        return @{ ExitCode = $exitCode; Output = $output }
    }
    finally {
        [Environment]::SetEnvironmentVariable('PATH', $savedPath, 'Process')
        foreach ($name in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedValues[$name], 'Process')
        }
    }
}

function Invoke-VerificationScript {
    param([hashtable]$Environment = @{})

    $savedValues = @{}
    foreach ($name in $Environment.Keys) {
        $savedValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $Environment[$name], 'Process')
    }

    $httpRequest = {
        param([string]$Uri)

        [IO.File]::AppendAllText($env:FL_K3S_TEST_HTTP_LOG, ($Uri + [Environment]::NewLine))
        if ($env:FL_K3S_TEST_HTTP_MODE -eq 'unhealthy' -and $Uri -like '*readiness') {
            return [pscustomobject]@{ StatusCode = 200; Content = '{"status":"DOWN"}' }
        }
        if ($env:FL_K3S_TEST_HTTP_MODE -eq 'readiness-error' -and $Uri -like '*readiness') {
            return [pscustomobject]@{ StatusCode = 503; Content = '{"status":"UP"}' }
        }
        if ($env:FL_K3S_TEST_HTTP_MODE -eq 'frontend-error' -and $Uri -eq 'https://localhost:8443/') {
            return [pscustomobject]@{ StatusCode = 503; Content = '' }
        }
        if ($Uri -like '*readiness') {
            return [pscustomobject]@{ StatusCode = 200; Content = '{"status":"UP"}' }
        }

        return [pscustomobject]@{ StatusCode = 200; Content = '<html />' }
    }

    try {
        try {
            $verifyKubectl = Join-Path $verifyShimRoot 'kubectl.cmd'
            $output = & $verifyScript -HttpRequest $httpRequest -KubectlCommand $verifyKubectl 2>&1 | Out-String
            $exitCode = 0
        }
        catch {
            $output = ($_ | Out-String)
            $exitCode = 1
        }

        return @{ ExitCode = $exitCode; Output = $output }
    }
    finally {
        foreach ($name in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedValues[$name], 'Process')
        }
    }
}

function Set-ShimMode {
    param([string]$Mode)

    [Environment]::SetEnvironmentVariable('FL_K3S_TEST_KUBECTL_MODE', $Mode, 'Process')
    [IO.File]::WriteAllText($kubectlLog, '')
    [IO.File]::WriteAllText($nerdctlLog, '')
    [IO.File]::WriteAllText($httpLog, '')
}

function Assert-NoMutations {
    param([string[]]$Lines)

    Assert-True (@($Lines | Where-Object { $_ -match '^(create|apply)\t' }).Count -eq 0) 'A failed guard must not run kubectl create or apply.'
}

function New-TestShims {
    New-Item -ItemType Directory -Force -Path $shimRoot | Out-Null
    New-Item -ItemType Directory -Force -Path $verifyShimRoot | Out-Null

    $kubectlShim = @'
$Arguments = @($env:FL_K3S_TEST_SHIM_ARGS -split ' ' | Where-Object { $_ -ne '' })

$sanitized = foreach ($argument in $Arguments) {
    if ($argument -like '--from-literal=*') {
        $key = $argument.Substring('--from-literal='.Length).Split('=')[0]
        '--from-literal=' + $key + '=<redacted>'
    }
    else {
        $argument
    }
}

[IO.File]::AppendAllText($env:FL_K3S_TEST_KUBECTL_LOG, (($sanitized -join "`t") + "`n"))

if (($Arguments.Count -eq 2) -and ($Arguments[0] -eq 'config') -and ($Arguments[1] -eq 'current-context')) {
    if ($env:FL_K3S_TEST_KUBECTL_MODE -eq 'wrong-context') {
        Write-Output 'old-cluster'
    }
    else {
        Write-Output 'rancher-desktop'
    }
    exit 0
}

if (($Arguments.Count -ge 2) -and ($Arguments[0] -eq 'get') -and ($Arguments -contains '--raw=/readyz')) {
    if ($env:FL_K3S_TEST_KUBECTL_MODE -eq 'unreachable') {
        Write-Error 'simulated unreachable API'
        exit 1
    }

    Write-Output 'ok'
    exit 0
}

if (($Arguments.Count -eq 6) -and ($Arguments[0] -eq '-n') -and ($Arguments[1] -eq 'flashsale') -and ($Arguments[2] -eq 'rollout') -and ($Arguments[3] -eq 'status')) {
    Write-Output 'successfully rolled out'
    exit 0
}

if (($Arguments -join ' ') -eq '-n flashsale get deployment backend -o jsonpath={.status.readyReplicas}') {
    if ($env:FL_K3S_TEST_KUBECTL_MODE -eq 'backend-not-ready') { Write-Output '0' } else { Write-Output '1' }
    exit 0
}

if (($Arguments.Count -ge 4) -and ($Arguments[0] -eq '-n') -and ($Arguments[1] -eq 'flashsale') -and ($Arguments[2] -eq 'get') -and ($Arguments[3] -eq 'pods')) {
    if ($env:FL_K3S_TEST_KUBECTL_MODE -eq 'restart') { Write-Output '1' } else { Write-Output '0' }
    exit 0
}

if (($Arguments -join ' ') -eq '-n flashsale get pvc --no-headers') {
    if ($env:FL_K3S_TEST_KUBECTL_MODE -eq 'wrong-pvc-count') {
        Write-Output 'pvc-one Bound'
        Write-Output 'pvc-two Bound'
    }
    else {
        Write-Output 'pvc-one Bound'
        Write-Output 'pvc-two Bound'
        Write-Output 'pvc-three Bound'
    }
    exit 0
}

if (($Arguments.Count -ge 1) -and ($Arguments[0] -eq 'create')) {
    Write-Output 'apiVersion: v1'
    exit 0
}

if (($Arguments.Count -ge 1) -and ($Arguments[0] -eq 'apply')) {
    Write-Output 'configured'
    exit 0
}

Write-Error ('Unexpected kubectl invocation: ' + ($Arguments -join ' '))
exit 1
'@
    $nerdctlShim = @'
$Arguments = @($env:FL_K3S_TEST_SHIM_ARGS -split ' ' | Where-Object { $_ -ne '' })

[IO.File]::AppendAllText($env:FL_K3S_TEST_NERDCTL_LOG, (($Arguments -join "`t") + "`n"))

if ($Arguments -contains 'build') { exit 0 }
if ($Arguments -contains 'images') {
    Write-Output 'flashsale-backend local'
    Write-Output 'flashsale-frontend local'
    Write-Output 'flashsale-nginx local'
    exit 0
}

Write-Error ('Unexpected nerdctl invocation: ' + ($Arguments -join ' '))
exit 1
'@
    $commandShim = '@set "FL_K3S_TEST_SHIM_ARGS=%*"' + "`r`n" + '@powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0{0}.ps1"' + "`r`n" + '@exit /b %ERRORLEVEL%' + "`r`n"

    [IO.File]::WriteAllText((Join-Path $shimRoot 'kubectl-shim.ps1'), $kubectlShim)
    [IO.File]::WriteAllText((Join-Path $shimRoot 'nerdctl-shim.ps1'), $nerdctlShim)
    [IO.File]::WriteAllText((Join-Path $shimRoot 'kubectl.cmd'), ($commandShim -f 'kubectl-shim'))
    [IO.File]::WriteAllText((Join-Path $shimRoot 'nerdctl.cmd'), ($commandShim -f 'nerdctl-shim'))

    $verifyKubectlShim = @'
@echo off
echo %*>> "%FL_K3S_TEST_KUBECTL_LOG%"
if "%1 %2"=="config current-context" (
  if "%FL_K3S_TEST_KUBECTL_MODE%"=="wrong-context" (echo old-cluster) else (echo rancher-desktop)
  exit /b 0
)
if "%1 %2 %3 %4"=="-n flashsale rollout status" (
  echo successfully rolled out
  exit /b 0
)
if "%1 %2 %3 %4"=="-n flashsale get deployment" (
  if "%FL_K3S_TEST_KUBECTL_MODE%"=="backend-not-ready" (echo 0) else (echo 1)
  exit /b 0
)
if "%1 %2 %3 %4"=="-n flashsale get pods" (
  if "%FL_K3S_TEST_KUBECTL_MODE%"=="restart" (echo 1) else (echo 0)
  exit /b 0
)
if "%1 %2 %3 %4"=="-n flashsale get pvc" (
  echo pvc-one Bound
  echo pvc-two Bound
  if not "%FL_K3S_TEST_KUBECTL_MODE%"=="wrong-pvc-count" echo pvc-three Bound
  exit /b 0
)
echo Unexpected kubectl invocation: %* 1>&2
exit /b 1
'@
    [IO.File]::WriteAllText((Join-Path $verifyShimRoot 'kubectl.cmd'), $verifyKubectlShim)
}

try {
    Assert-True (Test-Path -LiteralPath $buildScript) 'build-local.ps1 must exist.'
    Assert-True (Test-Path -LiteralPath $deployScript) 'deploy.ps1 must exist.'
    Assert-True (Test-Path -LiteralPath $verifyScript) 'verify.ps1 must exist.'
    Assert-True ((Get-Content -LiteralPath $verifyScript -Raw) -match 'Add-Type\s+-AssemblyName\s+System\.Net\.Http') 'verify.ps1 must load System.Net.Http before creating its PowerShell 5.1-compatible HTTPS client.'

    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    [IO.File]::WriteAllText($privateKeyPath, 'test-private-key')
    [IO.File]::WriteAllText($publicKeyPath, 'test-public-key')
    if (-not (Test-Path -LiteralPath $certificatePath)) {
        [IO.File]::WriteAllText($certificatePath, 'test-certificate')
        $createdCertificate = $true
    }
    if (-not (Test-Path -LiteralPath $certificateKeyPath)) {
        [IO.File]::WriteAllText($certificateKeyPath, 'test-certificate-key')
        $createdCertificateKey = $true
    }
    New-TestShims

    $runtimeEnvironment = @{
        FL_K3S_POSTGRES_PASSWORD = 'test-postgres-password'
        FL_K3S_RABBITMQ_PASSWORD = 'test-rabbitmq-password'
        FL_K3S_JWT_PRIVATE_KEY_PATH = $privateKeyPath
        FL_K3S_JWT_PUBLIC_KEY_PATH = $publicKeyPath
        FL_K3S_TEST_KUBECTL_LOG = $kubectlLog
        FL_K3S_TEST_NERDCTL_LOG = $nerdctlLog
    }

    $verificationEnvironment = @{
        FL_K3S_TEST_KUBECTL_LOG = $kubectlLog
        FL_K3S_TEST_HTTP_LOG = $httpLog
        FL_K3S_TEST_HTTP_MODE = 'healthy'
    }

    Set-ShimMode -Mode 'wrong-context'
    $verifyWrongContext = Invoke-VerificationScript -Environment $verificationEnvironment
    Assert-True ($verifyWrongContext.ExitCode -ne 0) 'verify.ps1 must reject a non-rancher-desktop context.'
    Assert-True ($verifyWrongContext.Output -match 'rancher-desktop') 'verify.ps1 must identify the required kubectl context.'
    $wrongContextLines = Get-LogLines -Path $kubectlLog
    Assert-True ($wrongContextLines.Count -eq 1) 'verify.ps1 must not inspect workloads before the context guard succeeds.'
    Assert-True ((Get-LogLines -Path $httpLog).Count -eq 0) 'verify.ps1 must not issue HTTP checks when the context guard rejects it.'

    Set-ShimMode -Mode 'reachable'
    $verifySuccess = Invoke-VerificationScript -Environment $verificationEnvironment
    Assert-True ($verifySuccess.ExitCode -eq 0) 'verify.ps1 must pass when all rollout, pod, PVC, and endpoint assertions succeed.'
    $verifyLines = Get-LogLines -Path $kubectlLog
    Assert-True (@($verifyLines | Where-Object { $_ -match '^-n\s+flashsale\s+rollout\s+status\s+' }).Count -eq 8) 'verify.ps1 must wait for all three StatefulSets and five Deployments.'
    Assert-True (@($verifyLines | Where-Object { $_ -match '^-n\s+flashsale\s+rollout\s+status\s+statefulset/postgres\s+--timeout=180s$' }).Count -eq 1) 'verify.ps1 must wait for PostgreSQL.'
    Assert-True (@($verifyLines | Where-Object { $_ -match '^-n\s+flashsale\s+rollout\s+status\s+deployment/nginx\s+--timeout=180s$' }).Count -eq 1) 'verify.ps1 must wait for Nginx.'
    Assert-True ((Get-LogLines -Path $httpLog) -join "`n" -match [regex]::Escape('https://localhost:8443/actuator/health/readiness')) 'verify.ps1 must check Backend readiness through Nginx.'
    Assert-True ((Get-LogLines -Path $httpLog) -join "`n" -match [regex]::Escape('https://localhost:8443/')) 'verify.ps1 must check the frontend route through Nginx.'

    Set-ShimMode -Mode 'backend-not-ready'
    $verifyBackendFailure = Invoke-VerificationScript -Environment $verificationEnvironment
    Assert-True ($verifyBackendFailure.ExitCode -ne 0) 'verify.ps1 must fail when the Backend does not have one ready Pod.'
    Assert-True ($verifyBackendFailure.Output -match 'Expected one ready Backend Pod') 'verify.ps1 must report a Backend ready Pod assertion failure.'

    Set-ShimMode -Mode 'restart'
    $verifyRestartFailure = Invoke-VerificationScript -Environment $verificationEnvironment
    Assert-True ($verifyRestartFailure.ExitCode -ne 0) 'verify.ps1 must fail when a container restart is reported.'
    Assert-True ($verifyRestartFailure.Output -match 'Expected zero container restarts') 'verify.ps1 must report a restart assertion failure.'

    Set-ShimMode -Mode 'wrong-pvc-count'
    $verifyPvcFailure = Invoke-VerificationScript -Environment $verificationEnvironment
    Assert-True ($verifyPvcFailure.ExitCode -ne 0) 'verify.ps1 must fail when exactly three PVCs are not present.'
    Assert-True ($verifyPvcFailure.Output -match 'Expected three PVCs') 'verify.ps1 must report a PVC assertion failure.'

    Set-ShimMode -Mode 'reachable'
    $unhealthyEnvironment = $verificationEnvironment.Clone()
    $unhealthyEnvironment.FL_K3S_TEST_HTTP_MODE = 'unhealthy'
    $verifyHealthFailure = Invoke-VerificationScript -Environment $unhealthyEnvironment
    Assert-True ($verifyHealthFailure.ExitCode -ne 0) 'verify.ps1 must fail when Backend readiness is not UP through Nginx.'
    Assert-True ($verifyHealthFailure.Output -match 'Backend readiness is not UP') 'verify.ps1 must report a readiness assertion failure.'

    Set-ShimMode -Mode 'reachable'
    $readinessErrorEnvironment = $verificationEnvironment.Clone()
    $readinessErrorEnvironment.FL_K3S_TEST_HTTP_MODE = 'readiness-error'
    $verifyReadinessStatusFailure = Invoke-VerificationScript -Environment $readinessErrorEnvironment
    Assert-True ($verifyReadinessStatusFailure.ExitCode -ne 0) 'verify.ps1 must fail when the readiness endpoint returns a non-200 status.'
    Assert-True ($verifyReadinessStatusFailure.Output -match 'Backend readiness is not UP') 'verify.ps1 must report a non-200 readiness response as a readiness failure.'

    Set-ShimMode -Mode 'reachable'
    $frontendErrorEnvironment = $verificationEnvironment.Clone()
    $frontendErrorEnvironment.FL_K3S_TEST_HTTP_MODE = 'frontend-error'
    $verifyFrontendFailure = Invoke-VerificationScript -Environment $frontendErrorEnvironment
    Assert-True ($verifyFrontendFailure.ExitCode -ne 0) 'verify.ps1 must fail when the frontend route is not HTTP 200.'
    Assert-True ($verifyFrontendFailure.Output -match 'Frontend route did not return 200') 'verify.ps1 must report a frontend route assertion failure.'

    Set-ShimMode -Mode 'wrong-context'
    $buildFailure = Invoke-LocalScript -ScriptPath $buildScript -Environment $runtimeEnvironment
    Assert-True ($buildFailure.ExitCode -ne 0) 'build-local.ps1 must reject a non-rancher-desktop context.'
    Assert-True ($buildFailure.Output -match 'rancher-desktop') 'build-local.ps1 must identify the required kubectl context.'
    Assert-True ((Get-LogLines -Path $nerdctlLog).Count -eq 0) 'build-local.ps1 must not build when the context guard fails.'

    Set-ShimMode -Mode 'reachable'
    $buildSuccess = Invoke-LocalScript -ScriptPath $buildScript -Environment $runtimeEnvironment
    Assert-True ($buildSuccess.ExitCode -eq 0) 'build-local.ps1 must build images with Rancher Desktop shims.'
    $nerdctlLines = Get-LogLines -Path $nerdctlLog
    $buildLines = @($nerdctlLines | Where-Object { $_ -match "`tbuild`t" })
    Assert-True ($buildLines.Count -eq 3) 'build-local.ps1 must build exactly three images.'
    foreach ($tag in @('flashsale-backend:local', 'flashsale-frontend:local', 'flashsale-nginx:local')) {
        Assert-True (@($buildLines | Where-Object { ($_ -match "^--namespace`tk8s.io`tbuild`t--tag`t$([regex]::Escape($tag))`t") }).Count -eq 1) "build-local.ps1 must build $tag in containerd namespace k8s.io."
    }

    Set-ShimMode -Mode 'unreachable'
    $unreachableDeploy = Invoke-LocalScript -ScriptPath $deployScript -Environment $runtimeEnvironment
    Assert-True ($unreachableDeploy.ExitCode -ne 0) 'deploy.ps1 must abort when the Rancher Desktop API probe fails.'
    Assert-True ($unreachableDeploy.Output -match 'reach') 'deploy.ps1 must report an API reachability failure.'
    $unreachableLines = Get-LogLines -Path $kubectlLog
    Assert-True (@($unreachableLines | Where-Object { $_ -match '^get\t--raw=/readyz\t--request-timeout=5s$' }).Count -eq 1) 'deploy.ps1 must perform one bounded non-mutating /readyz probe.'
    Assert-NoMutations -Lines $unreachableLines

    Set-ShimMode -Mode 'reachable'
    $deploySuccess = Invoke-LocalScript -ScriptPath $deployScript -Environment $runtimeEnvironment
    Assert-True ($deploySuccess.ExitCode -eq 0) 'deploy.ps1 must complete with a reachable Rancher Desktop API shim.'
    $deployLines = Get-LogLines -Path $kubectlLog
    $probeIndex = [Array]::IndexOf([string[]]$deployLines, @($deployLines | Where-Object { $_ -match '^get\t--raw=/readyz\t--request-timeout=5s$' })[0])
    $firstMutationIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match '^(create|apply)\t' })
    Assert-True (($probeIndex -ge 0) -and ($firstMutationIndex -gt $probeIndex)) 'deploy.ps1 must probe the API before every mutation.'
    $genericSecret = @($deployLines | Where-Object { $_ -match '^create\tsecret\tgeneric\tflashsale-secrets\t--namespace\tflashsale' })
    Assert-True ($genericSecret.Count -eq 1) 'deploy.ps1 must create the flashsale runtime Secret.'
    foreach ($key in @('POSTGRES_PASSWORD', 'SPRING_RABBITMQ_PASSWORD', 'JWT_PRIVATE_KEY', 'JWT_PUBLIC_KEY')) {
        Assert-True ($genericSecret[0] -match ([regex]::Escape("--from-literal=$key=<redacted>"))) "deploy.ps1 must map $key into flashsale-secrets."
    }
    $tlsSecret = @($deployLines | Where-Object { $_ -match '^create\tsecret\ttls\tflashsale-local-tls\t--namespace\tflashsale' })
    Assert-True ($tlsSecret.Count -eq 1) 'deploy.ps1 must create the flashsale TLS Secret.'
    Assert-True ($tlsSecret[0] -match ([regex]::Escape("--cert=$certificatePath"))) 'deploy.ps1 must pass the generated Nginx certificate to kubectl.'
    Assert-True ($tlsSecret[0] -match ([regex]::Escape("--key=$certificateKeyPath"))) 'deploy.ps1 must pass the generated Nginx key to kubectl.'
    Assert-True ($deploySuccess.Output -notmatch 'test-postgres-password|test-rabbitmq-password|test-private-key|test-public-key') 'deploy.ps1 must not print decoded runtime secret values.'

    $deployFailure = Invoke-LocalScript -ScriptPath $deployScript -Environment @{
        FL_K3S_POSTGRES_PASSWORD = $null
        FL_K3S_RABBITMQ_PASSWORD = $null
        FL_K3S_JWT_PRIVATE_KEY_PATH = $null
        FL_K3S_JWT_PUBLIC_KEY_PATH = $null
        FL_K3S_TEST_KUBECTL_LOG = $kubectlLog
        FL_K3S_TEST_NERDCTL_LOG = $nerdctlLog
    }
    Assert-True ($deployFailure.ExitCode -ne 0) 'deploy.ps1 must reject missing runtime secret inputs.'
    Assert-True ($deployFailure.Output -match 'FL_K3S_') 'deploy.ps1 must name the missing runtime secret environment variable.'

    Write-Host 'PASS: k8s scripts use deterministic offline shims for guards, builds, deployment, and secret safety.'
}
finally {
    if ($createdCertificate -and (Test-Path -LiteralPath $certificatePath)) { Remove-Item -LiteralPath $certificatePath -Force }
    if ($createdCertificateKey -and (Test-Path -LiteralPath $certificateKeyPath)) { Remove-Item -LiteralPath $certificateKeyPath -Force }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
