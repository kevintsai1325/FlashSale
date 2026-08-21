$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$buildScript = Join-Path $repo 'scripts\k8s\build-local.ps1'
$deployScript = Join-Path $repo 'scripts\k8s\deploy.ps1'
$powershell51 = (Get-Command powershell.exe -ErrorAction Stop).Source
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('flashsale-k8s-script-test-' + [Guid]::NewGuid().ToString('N'))
$shimRoot = Join-Path $testRoot 'bin'
$kubectlLog = Join-Path $testRoot 'kubectl.log'
$nerdctlLog = Join-Path $testRoot 'nerdctl.log'
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

function Set-ShimMode {
    param([string]$Mode)

    [Environment]::SetEnvironmentVariable('FL_K3S_TEST_KUBECTL_MODE', $Mode, 'Process')
    [IO.File]::WriteAllText($kubectlLog, '')
    [IO.File]::WriteAllText($nerdctlLog, '')
}

function Assert-NoMutations {
    param([string[]]$Lines)

    Assert-True (@($Lines | Where-Object { $_ -match '^(create|apply)\t' }).Count -eq 0) 'A failed guard must not run kubectl create or apply.'
}

function New-TestShims {
    New-Item -ItemType Directory -Force -Path $shimRoot | Out-Null

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
}

try {
    Assert-True (Test-Path -LiteralPath $buildScript) 'build-local.ps1 must exist.'
    Assert-True (Test-Path -LiteralPath $deployScript) 'deploy.ps1 must exist.'

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
