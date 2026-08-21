$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$buildScript = Join-Path $repo 'scripts\k8s\build-local.ps1'
$deployScript = Join-Path $repo 'scripts\k8s\deploy.ps1'
$powershell51 = (Get-Command powershell.exe -ErrorAction Stop).Source

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) { throw $Message }
}

function Invoke-ScriptForFailure {
    param(
        [string]$ScriptPath,
        [hashtable]$Environment = @{}
    )

    $savedValues = @{}
    foreach ($name in $Environment.Keys) {
        $savedValues[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $Environment[$name], 'Process')
    }

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
        foreach ($name in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $savedValues[$name], 'Process')
        }
    }
}

Assert-True (Test-Path -LiteralPath $buildScript) 'build-local.ps1 must exist.'
Assert-True (Test-Path -LiteralPath $deployScript) 'deploy.ps1 must exist.'

$buildFailure = Invoke-ScriptForFailure -ScriptPath $buildScript
Assert-True ($buildFailure.ExitCode -ne 0) 'build-local.ps1 must reject a non-rancher-desktop context.'
Assert-True ($buildFailure.Output -match 'rancher-desktop') 'build-local.ps1 must identify the required kubectl context.'

$deployFailure = Invoke-ScriptForFailure -ScriptPath $deployScript -Environment @{
    FL_K3S_POSTGRES_PASSWORD = $null
    FL_K3S_RABBITMQ_PASSWORD = $null
    FL_K3S_JWT_PRIVATE_KEY_PATH = $null
    FL_K3S_JWT_PUBLIC_KEY_PATH = $null
}
Assert-True ($deployFailure.ExitCode -ne 0) 'deploy.ps1 must reject missing runtime secret inputs.'
Assert-True ($deployFailure.Output -match 'FL_K3S_') 'deploy.ps1 must name the missing runtime secret environment variable.'

$missingKeyFailure = Invoke-ScriptForFailure -ScriptPath $deployScript -Environment @{
    FL_K3S_POSTGRES_PASSWORD = 'not-a-real-password'
    FL_K3S_RABBITMQ_PASSWORD = 'not-a-real-password'
    FL_K3S_JWT_PRIVATE_KEY_PATH = (Join-Path $repo 'does-not-exist-private.pem')
    FL_K3S_JWT_PUBLIC_KEY_PATH = (Join-Path $repo 'does-not-exist-public.pem')
}
Assert-True ($missingKeyFailure.ExitCode -ne 0) 'deploy.ps1 must reject an unavailable JWT key file before contacting Kubernetes.'
Assert-True ($missingKeyFailure.Output -match 'JWT private key') 'deploy.ps1 must identify the unavailable JWT key file.'
Assert-True ($missingKeyFailure.Output -notmatch 'not-a-real-password') 'deploy.ps1 must not echo runtime secret values on validation failures.'

Write-Host 'PASS: k8s scripts reject unsafe context and missing runtime secret inputs offline.'
