$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$buildScript = Join-Path $repo 'scripts\k8s\build-local.ps1'
$deployScript = Join-Path $repo 'scripts\k8s\deploy.ps1'
$verifyScript = Join-Path $repo 'scripts\k8s\verify.ps1'
$preflightScript = Join-Path $repo 'scripts\k8s\k8s-preflight.ps1'
$powershell51 = (Get-Command powershell.exe -ErrorAction Stop).Source
$powershell7 = (Get-Command pwsh.exe -ErrorAction Stop).Source
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('flashsale-k8s-script-test-' + [Guid]::NewGuid().ToString('N'))
$shimRoot = Join-Path $testRoot 'bin'
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
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-LogLines {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    return @(Get-Content -LiteralPath $Path)
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
    Assert-True (@($Lines | Where-Object { $_ -match "\t(apply|rollout\trestart)\t" }).Count -eq 0) 'A failed guard must not apply resources or restart workloads.'
}

function Invoke-LocalScript {
    param(
        [string]$ScriptPath,
        [hashtable]$Environment = @{},
        [string]$PowerShellCommand = $powershell51
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
            $output = & $PowerShellCommand -NoProfile -ExecutionPolicy Bypass -File $ScriptPath 2>&1 | Out-String
            $exitCode = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $previousPreference }
        return @{ ExitCode = $exitCode; Output = $output }
    }
    finally {
        [Environment]::SetEnvironmentVariable('PATH', $savedPath, 'Process')
        foreach ($name in $Environment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedValues[$name], 'Process') }
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
        if ($Uri -like '*readiness') { return [pscustomobject]@{ StatusCode = 200; Content = '{"status":"UP"}' } }
        return [pscustomobject]@{ StatusCode = 200; Content = '<html />' }
    }
    try {
        try {
            $output = & $verifyScript -HttpRequest $httpRequest -KubectlCommand (Join-Path $shimRoot 'kubectl.cmd') 2>&1 | Out-String
            return @{ ExitCode = 0; Output = $output }
        }
        catch { return @{ ExitCode = 1; Output = ($_ | Out-String) } }
    }
    finally {
        foreach ($name in $Environment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedValues[$name], 'Process') }
    }
}

function New-TestShims {
    New-Item -ItemType Directory -Force -Path $shimRoot | Out-Null
    $kubectlShim = @'
$Arguments = @($env:FL_K3S_TEST_SHIM_ARGS -split ' ' | Where-Object { $_ -ne '' })
[IO.File]::AppendAllText($env:FL_K3S_TEST_KUBECTL_LOG, (($Arguments -join "`t") + "`n"))
$mode = $env:FL_K3S_TEST_KUBECTL_MODE

if (($Arguments -join ' ') -eq 'config current-context') {
    if ($mode -eq 'wrong-context') { 'old-cluster' } else { 'rancher-desktop' }
    exit 0
}
if (($Arguments -join ' ') -eq '--context rancher-desktop version -o json') {
    $clientMinor = if ($mode -eq 'unsupported-client') { '23' } else { '36' }
    $serverMinor = if ($mode -eq 'version-skew') { '39+' } else { '36+' }
    @{ clientVersion = @{ major = '1'; minor = $clientMinor; gitVersion = "v1.$clientMinor.0" }; serverVersion = @{ major = '1'; minor = $serverMinor; gitVersion = "v1.$serverMinor.0+k3s" } } | ConvertTo-Json -Compress
    exit 0
}
if (($Arguments -join ' ') -eq '--context rancher-desktop get --raw=/readyz --request-timeout=5s') {
    if ($mode -eq 'unreachable') { Write-Error 'simulated unreachable API'; exit 1 }
    'ok'; exit 0
}
if (($Arguments -join ' ') -like '--context rancher-desktop apply --server-side --dry-run=server -k *') { 'server dry-run passed'; exit 0 }
if (($Arguments -join ' ') -eq '--context rancher-desktop get namespace flashsale --ignore-not-found -o name') {
    if ($mode -in @('existing', 'password-change', 'missing-secret', 'empty-state')) { 'namespace/flashsale' }
    exit 0
}
if (($Arguments -join ' ') -eq '--context rancher-desktop -n flashsale get secret flashsale-secrets --ignore-not-found -o json') {
    if ($mode -in @('existing', 'password-change')) {
        $pg = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('test-postgres-password'))
        $rabbit = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('test-rabbitmq-password'))
        @{ apiVersion = 'v1'; kind = 'Secret'; metadata = @{ name = 'flashsale-secrets' }; data = @{ POSTGRES_PASSWORD = $pg; SPRING_RABBITMQ_PASSWORD = $rabbit } } | ConvertTo-Json -Compress
    }
    exit 0
}
if (($Arguments -join ' ') -eq '--context rancher-desktop -n flashsale get pvc --no-headers') {
    if ($mode -eq 'missing-secret') { 'data-postgres-0 Bound' }
    elseif ($mode -eq 'empty-state') { Write-Error 'No resources found in flashsale namespace.' }
    else { 'pvc-one Bound'; 'pvc-two Bound'; 'pvc-three Bound' }
    exit 0
}
if (($Arguments -join ' ') -eq '--context rancher-desktop -n flashsale get pvc -o name') {
    if ($mode -eq 'missing-secret') { 'persistentvolumeclaim/data-postgres-0' }
    exit 0
}
if (($Arguments -join ' ') -like '--context rancher-desktop apply -k * -l flashsale.dev/stage=*') { 'configured'; exit 0 }
if (($Arguments -join ' ') -eq '--context rancher-desktop apply -f -') {
    $json = [Console]::In.ReadToEnd() | ConvertFrom-Json
    if ($json.kind -ne 'Secret') { Write-Error 'stdin object was not a Secret'; exit 1 }
    [IO.File]::AppendAllText($env:FL_K3S_TEST_KUBECTL_LOG, ("stdin-secret`t" + $json.metadata.name + "`t" + (($json.data.PSObject.Properties.Name | Sort-Object) -join ',') + "`n"))
    'secret configured'; exit 0
}
if (($Arguments.Count -ge 7) -and ($Arguments[0] -eq '--context') -and ($Arguments[2] -eq '-n') -and ($Arguments[4] -eq 'rollout')) {
    if (($Arguments[5] -eq 'status') -or ($Arguments[5] -eq 'restart')) { 'successfully rolled out'; exit 0 }
}
if (($Arguments -join ' ') -like '--context rancher-desktop -n flashsale get pods -l app=* -o json') {
    $selector = @($Arguments | Where-Object { $_ -like 'app=*' })[0]
    $app = $selector.Substring(4)
    @{ items = @(
        @{ metadata = @{ name = "${app}-1" }; spec = @{ containers = @(@{ image = "flashsale-$app`:local" }) }; status = @{ containerStatuses = @(@{ imageID = "sha256:$app" }) } }
    ) } | ConvertTo-Json -Depth 8 -Compress
    exit 0
}
if (($Arguments -join ' ') -like '--context rancher-desktop -n flashsale get deployment backend -o jsonpath=*') { '1'; exit 0 }
if (($Arguments.Count -ge 9) -and (($Arguments[0..8] -join ' ') -eq '--context rancher-desktop -n flashsale get pods -l app -o')) {
    1..8 | ForEach-Object { "pod-$_,Running,True" }; exit 0
}
if (($Arguments.Count -ge 7) -and (($Arguments[0..6] -join ' ') -eq '--context rancher-desktop -n flashsale get pods -o')) { '0'; exit 0 }
Write-Error ('Unexpected kubectl invocation: ' + ($Arguments -join ' ')); exit 1
'@
    $nerdctlShim = @'
$Arguments = @($env:FL_K3S_TEST_SHIM_ARGS -split ' ' | Where-Object { $_ -ne '' })
[IO.File]::AppendAllText($env:FL_K3S_TEST_NERDCTL_LOG, (($Arguments -join "`t") + "`n"))
if ($Arguments -contains 'build') { exit 0 }
if ($Arguments -contains 'images') { 'flashsale-backend local'; 'flashsale-frontend local'; 'flashsale-nginx local'; exit 0 }
Write-Error ('Unexpected nerdctl invocation: ' + ($Arguments -join ' ')); exit 1
'@
    $commandShim = '@set "FL_K3S_TEST_SHIM_ARGS=%*"' + "`r`n" + '@powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0{0}.ps1"' + "`r`n" + '@exit /b %ERRORLEVEL%' + "`r`n"
    [IO.File]::WriteAllText((Join-Path $shimRoot 'kubectl-shim.ps1'), $kubectlShim)
    [IO.File]::WriteAllText((Join-Path $shimRoot 'nerdctl-shim.ps1'), $nerdctlShim)
    [IO.File]::WriteAllText((Join-Path $shimRoot 'kubectl.cmd'), ($commandShim -f 'kubectl-shim'))
    [IO.File]::WriteAllText((Join-Path $shimRoot 'nerdctl.cmd'), ($commandShim -f 'nerdctl-shim'))
}

try {
    foreach ($path in @($buildScript, $deployScript, $verifyScript, $preflightScript)) { Assert-True (Test-Path -LiteralPath $path) "Required script is missing: $path" }
    $buildContent = Get-Content -LiteralPath $buildScript -Raw
    $deployContent = Get-Content -LiteralPath $deployScript -Raw
    $verifyContent = Get-Content -LiteralPath $verifyScript -Raw
    foreach ($content in @($buildContent, $deployContent, $verifyContent)) {
        Assert-True ($content -match 'k8s-preflight\.ps1') 'Every helper must dot-source the reusable Kubernetes preflight.'
        Assert-True ($content -match 'Assert-KubernetesPreflight') 'Every helper must invoke the reusable Kubernetes preflight.'
    }
    Assert-True ($verifyContent -match 'Timeout\s*=\s*\[TimeSpan\]::FromSeconds\(10\)') 'verify.ps1 must bound HTTPS requests to ten seconds.'

    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    [IO.File]::WriteAllText($privateKeyPath, 'test-private-key')
    [IO.File]::WriteAllText($publicKeyPath, 'test-public-key')
    if (-not (Test-Path -LiteralPath $certificatePath)) { [IO.File]::WriteAllText($certificatePath, 'test-certificate'); $createdCertificate = $true }
    if (-not (Test-Path -LiteralPath $certificateKeyPath)) { [IO.File]::WriteAllText($certificateKeyPath, 'test-certificate-key'); $createdCertificateKey = $true }
    New-TestShims

    $commonEnvironment = @{
        FL_K3S_POSTGRES_PASSWORD = 'test-postgres-password'
        FL_K3S_RABBITMQ_PASSWORD = 'test-rabbitmq-password'
        FL_K3S_JWT_PRIVATE_KEY_PATH = $privateKeyPath
        FL_K3S_JWT_PUBLIC_KEY_PATH = $publicKeyPath
        FL_K3S_TEST_KUBECTL_LOG = $kubectlLog
        FL_K3S_TEST_NERDCTL_LOG = $nerdctlLog
    }
    $verificationEnvironment = @{ FL_K3S_TEST_KUBECTL_LOG = $kubectlLog; FL_K3S_TEST_HTTP_LOG = $httpLog; FL_K3S_TEST_HTTP_MODE = 'healthy' }

    Set-ShimMode 'wrong-context'
    $wrongContextResult = Invoke-LocalScript $buildScript $commonEnvironment
    Assert-True ($wrongContextResult.ExitCode -ne 0) 'Shared preflight must reject a non-rancher-desktop active context.'
    Assert-True ($wrongContextResult.Output -match 'rancher-desktop') 'Context rejection must identify rancher-desktop.'
    Assert-NoMutations (Get-LogLines $kubectlLog)
    Assert-True (@(Get-LogLines $nerdctlLog).Count -eq 0) 'Context rejection must occur before image builds.'

    Set-ShimMode 'unsupported-client'
    foreach ($script in @($buildScript, $deployScript)) {
        $result = Invoke-LocalScript $script $commonEnvironment
        Assert-True ($result.ExitCode -ne 0) "$script must reject kubectl v1.23."
        Assert-True ($result.Output -match '1\.35.*1\.37|1.35-1.37') "$script must provide an actionable supported kubectl range."
        Assert-NoMutations (Get-LogLines $kubectlLog)
        Assert-True (@(Get-LogLines $nerdctlLog).Count -eq 0) 'Version rejection must happen before image builds.'
        Set-ShimMode 'unsupported-client'
    }

    Set-ShimMode 'version-skew'
    $skewResult = Invoke-LocalScript $deployScript $commonEnvironment
    Assert-True ($skewResult.ExitCode -ne 0) 'deploy.ps1 must reject client/server minor skew above one.'
    Assert-True ($skewResult.Output -match 'skew') 'The version-skew rejection must identify version skew.'
    Assert-NoMutations (Get-LogLines $kubectlLog)

    Set-ShimMode 'unreachable'
    $unreachableResult = Invoke-LocalScript $deployScript $commonEnvironment
    Assert-True ($unreachableResult.ExitCode -ne 0) 'deploy.ps1 must stop when the bounded API readiness probe fails.'
    Assert-True ($unreachableResult.Output -match 'probing.*API') 'API rejection must identify the failed readiness probe.'
    Assert-NoMutations (Get-LogLines $kubectlLog)

    Set-ShimMode 'reachable'
    $buildResult = Invoke-LocalScript $buildScript $commonEnvironment
    Assert-True ($buildResult.ExitCode -eq 0) 'build-local.ps1 must pass with a supported reachable shim.'
    Assert-True (@(Get-LogLines $nerdctlLog | Where-Object { $_ -match "\tbuild\t" }).Count -eq 3) 'build-local.ps1 must build exactly three local images.'

    Set-ShimMode 'reachable'
    $buildPowerShell7Result = Invoke-LocalScript -ScriptPath $buildScript -Environment $commonEnvironment -PowerShellCommand $powershell7
    Assert-True ($buildPowerShell7Result.ExitCode -eq 0) 'build-local.ps1 must execute successfully under PowerShell 7.'

    Set-ShimMode 'reachable'
    $deployResult = Invoke-LocalScript $deployScript $commonEnvironment
    Assert-True ($deployResult.ExitCode -eq 0) "First deploy must pass with complete initial secret inputs. Output: $($deployResult.Output)"
    $deployLines = Get-LogLines $kubectlLog
    $dryRunIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'apply\t--server-side\t--dry-run=server' })
    $firstApplyIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match '--context\trancher-desktop\tapply\t-k' })
    $bootstrapIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'stage=bootstrap' })
    $foundationIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'stage=foundation' })
    $dependencyIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'stage=dependency' })
    $applicationIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'stage=application' })
    $backendRestartIndex = [Array]::FindIndex([string[]]$deployLines, [Predicate[string]]{ param($line) $line -match 'rollout\trestart\tdeployment/backend' })
    Assert-True (($firstApplyIndex -eq $bootstrapIndex) -and ($bootstrapIndex -lt $dryRunIndex) -and ($dryRunIndex -lt $foundationIndex)) 'Only Namespace bootstrap may precede the full server-side dry-run.'
    Assert-True (($foundationIndex -lt $dependencyIndex) -and ($dependencyIndex -lt $applicationIndex) -and ($applicationIndex -lt $backendRestartIndex)) 'Deploy must apply foundation, dependencies, application, then restart local-image workloads.'
    Assert-True (@($deployLines | Where-Object { $_ -match '^stdin-secret\tflashsale-secrets\t' }).Count -eq 1) 'Runtime Secret must be applied through stdin exactly once.'
    Assert-True (@($deployLines | Where-Object { $_ -match '^stdin-secret\tflashsale-local-tls\t' }).Count -eq 1) 'TLS Secret must be applied through stdin exactly once.'
    Assert-True (@($deployLines | Where-Object { $_ -notmatch '^(config\tcurrent-context|--context\trancher-desktop|stdin-secret\t)' }).Count -eq 0) 'Every deploy kubectl operation after current-context must explicitly select rancher-desktop.'
    Assert-True (($deployLines -join "`n") -notmatch 'test-postgres-password|test-rabbitmq-password|test-private-key|test-public-key') 'kubectl arguments/logs must not expose decoded secrets.'
    Assert-True ($deployResult.Output -notmatch 'test-postgres-password|test-rabbitmq-password|test-private-key|test-public-key') 'deploy output must not expose decoded secrets.'
    Assert-True (@($deployLines | Where-Object { $_ -match 'rollout\trestart\tdeployment/(backend|frontend|nginx)' }).Count -eq 3) 'Deploy must restart backend, frontend, and nginx after application apply.'
    Assert-True (@($deployLines | Where-Object { $_ -match 'rollout\tstatus\t(statefulset/(postgres|redis|rabbitmq)|deployment/(mailpit|zipkin))' }).Count -eq 5) 'Deploy must wait for all five dependencies before applying the application stage.'

    Set-ShimMode 'reachable'
    $deployPowerShell7Result = Invoke-LocalScript -ScriptPath $deployScript -Environment $commonEnvironment -PowerShellCommand $powershell7
    Assert-True ($deployPowerShell7Result.ExitCode -eq 0) 'deploy.ps1 must execute successfully under PowerShell 7.'
    Assert-True ($deployPowerShell7Result.Output -notmatch 'test-postgres-password|test-rabbitmq-password|test-private-key|test-public-key') 'PowerShell 7 deploy output must not expose decoded secrets.'

    Set-ShimMode 'existing'
    $reuseEnvironment = $commonEnvironment.Clone()
    $reuseEnvironment.FL_K3S_POSTGRES_PASSWORD = $null
    $reuseEnvironment.FL_K3S_RABBITMQ_PASSWORD = $null
    $reuseResult = Invoke-LocalScript $deployScript $reuseEnvironment
    Assert-True ($reuseResult.ExitCode -eq 0) 'Redeploy must reuse stored stateful credentials when password inputs are omitted.'

    Set-ShimMode 'empty-state'
    $emptyStateResult = Invoke-LocalScript $deployScript $commonEnvironment
    Assert-True ($emptyStateResult.ExitCode -eq 0) 'An existing empty namespace must still support safe first credential creation.'
    Assert-True (@(Get-LogLines $kubectlLog | Where-Object { $_ -match 'get\tpvc\t-o\tname$' }).Count -eq 1) 'PVC safety detection must use machine-readable names, not human no-resource output.'

    Set-ShimMode 'password-change'
    $changedEnvironment = $commonEnvironment.Clone()
    $changedEnvironment.FL_K3S_POSTGRES_PASSWORD = 'attempted-rotation'
    $changeResult = Invoke-LocalScript $deployScript $changedEnvironment
    Assert-True ($changeResult.ExitCode -ne 0) 'Redeploy must reject an attempted stateful password change.'
    Assert-True ($changeResult.Output -match 'coordinated rotation') 'Password rejection must direct the operator to coordinated rotation.'
    Assert-NoMutations (Get-LogLines $kubectlLog)

    Set-ShimMode 'missing-secret'
    $missingSecretResult = Invoke-LocalScript $deployScript $commonEnvironment
    Assert-True ($missingSecretResult.ExitCode -ne 0) 'Deploy must stop when PVCs exist but the stateful credential Secret is missing.'
    Assert-True ($missingSecretResult.Output -match 'PVC') 'Missing credential state must explain the PVC safety conflict.'
    Assert-NoMutations (Get-LogLines $kubectlLog)

    Set-ShimMode 'reachable'
    $verifyResult = Invoke-VerificationScript $verificationEnvironment
    Assert-True ($verifyResult.ExitCode -eq 0) "verify.ps1 must pass all deterministic workload and endpoint checks. Output: $($verifyResult.Output) Log: $((Get-LogLines $kubectlLog) -join ' || ')"
    $verifyLines = Get-LogLines $kubectlLog
    Assert-True (@($verifyLines | Where-Object { $_ -notmatch '^(config\tcurrent-context|--context\trancher-desktop)' }).Count -eq 0) 'Every kubectl operation after current-context must explicitly select rancher-desktop.'
    Assert-True (@($verifyLines | Where-Object { $_ -match 'rollout\tstatus' }).Count -eq 8) 'verify.ps1 must wait for exactly eight workloads.'
    Assert-True (@(Get-LogLines $httpLog).Count -eq 2) 'verify.ps1 must execute both HTTPS endpoint checks.'

    Set-ShimMode 'unsupported-client'
    $verifyVersionResult = Invoke-VerificationScript $verificationEnvironment
    Assert-True ($verifyVersionResult.ExitCode -ne 0) 'verify.ps1 must reject unsupported kubectl before workload checks.'
    Assert-True (@(Get-LogLines $kubectlLog | Where-Object { $_ -match 'rollout|get\tpods' }).Count -eq 0) 'verify.ps1 must not inspect workloads after version rejection.'
    Assert-True (@(Get-LogLines $httpLog).Count -eq 0) 'verify.ps1 must not call HTTP endpoints after version rejection.'

    Write-Host 'PASS: k8s scripts enforce shared version-safe preflight, staged deployment, create-once credentials, stdin Secret safety, local rollouts, and deterministic verification.'
}
finally {
    if ($createdCertificate -and (Test-Path -LiteralPath $certificatePath)) { Remove-Item -LiteralPath $certificatePath -Force }
    if ($createdCertificateKey -and (Test-Path -LiteralPath $certificateKeyPath)) { Remove-Item -LiteralPath $certificateKeyPath -Force }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
