Set-StrictMode -Version Latest

$script:RancherDesktopContext = 'rancher-desktop'

function Assert-CommandAvailable {
    param([string]$Name)
    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required but was not found on PATH."
    }
}

function Invoke-KubectlChecked {
    param(
        [string]$KubectlCommand = 'kubectl',
        [string[]]$Arguments,
        [string]$Operation
    )
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $KubectlCommand @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw "kubectl failed while $Operation." }
    return $output
}

function Get-KubernetesMinor {
    param([object]$Version, [string]$Description)
    if ($null -eq $Version -or [string]$Version.major -ne '1') { throw "$Description must be Kubernetes major version 1." }
    $match = [regex]::Match([string]$Version.minor, '^\d+')
    if (-not $match.Success) { throw "Unable to parse $Description minor version." }
    return [int]$match.Value
}

function Assert-KubernetesPreflight {
    param([string]$KubectlCommand = 'kubectl')
    Assert-CommandAvailable -Name $KubectlCommand
    $context = (Invoke-KubectlChecked -KubectlCommand $KubectlCommand -Arguments @('config', 'current-context') -Operation 'reading the current context' | Out-String).Trim()
    if ($context -ne $script:RancherDesktopContext) {
        throw 'Select the rancher-desktop kubectl context first. No build, mutation, or workload check was attempted.'
    }
    $versionText = (Invoke-KubectlChecked -KubectlCommand $KubectlCommand -Arguments @('--context', $script:RancherDesktopContext, 'version', '-o', 'json') -Operation 'reading Kubernetes client/server versions' | Out-String)
    try { $versions = $versionText | ConvertFrom-Json }
    catch { throw 'Unable to parse kubectl version -o json output.' }
    $clientMinor = Get-KubernetesMinor -Version $versions.clientVersion -Description 'kubectl client'
    $serverMinor = Get-KubernetesMinor -Version $versions.serverVersion -Description 'Kubernetes server'
    if ($clientMinor -lt 35 -or $clientMinor -gt 37) {
        throw "kubectl client 1.$clientMinor is unsupported. Install kubectl 1.35-1.37 (prefer 1.36) and select that executable on PATH."
    }
    if ([Math]::Abs($clientMinor - $serverMinor) -gt 1) {
        throw "Unsupported kubectl/Kubernetes minor-version skew: client 1.$clientMinor, server 1.$serverMinor. Select a client within +/-1 minor (prefer 1.36 for this Rancher Desktop server)."
    }
    Invoke-KubectlChecked -KubectlCommand $KubectlCommand -Arguments @('--context', $script:RancherDesktopContext, 'get', '--raw=/readyz', '--request-timeout=5s') -Operation 'probing the rancher-desktop Kubernetes API' | Out-Null
    return [pscustomobject]@{ Context = $script:RancherDesktopContext; ClientMinor = $clientMinor; ServerMinor = $serverMinor }
}
