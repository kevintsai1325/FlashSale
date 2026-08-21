$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Assert-CommandAvailable {
    param([string]$Name)

    if ($null -eq (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name is required but was not found on PATH."
    }
}

function Get-RequiredEnvironmentValue {
    param([string]$Name)

    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ([String]::IsNullOrWhiteSpace($value)) {
        throw "Required environment variable is missing or empty: $Name"
    }

    return $value
}

function Get-RequiredFileContent {
    param(
        [string]$Path,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description was not found: $Path"
    }

    $content = Get-Content -LiteralPath $Path -Raw
    if ([String]::IsNullOrWhiteSpace($content)) {
        throw "$Description is empty: $Path"
    }

    return $content
}

function Assert-RancherDesktopContext {
    $context = (& kubectl config current-context | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to read the current kubectl context.'
    }

    if ($context -ne 'rancher-desktop') {
        throw 'Select the rancher-desktop kubectl context first.'
    }
}

function Assert-KubernetesApiReachable {
    & kubectl get --raw=/readyz --request-timeout=5s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to reach the Kubernetes API for the rancher-desktop context. No resources were changed.'
    }
}

function Invoke-KubectlYaml {
    param(
        [string[]]$Arguments,
        [string]$Operation
    )

    $yaml = & kubectl @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed while $Operation."
    }

    return $yaml
}

function Apply-KubectlYaml {
    param(
        [object[]]$Yaml,
        [string]$Operation
    )

    $Yaml | & kubectl apply -f -
    if ($LASTEXITCODE -ne 0) {
        throw "kubectl failed while $Operation."
    }
}

$postgresPassword = Get-RequiredEnvironmentValue -Name 'FL_K3S_POSTGRES_PASSWORD'
$rabbitMqPassword = Get-RequiredEnvironmentValue -Name 'FL_K3S_RABBITMQ_PASSWORD'
$privateKeyPath = Get-RequiredEnvironmentValue -Name 'FL_K3S_JWT_PRIVATE_KEY_PATH'
$publicKeyPath = Get-RequiredEnvironmentValue -Name 'FL_K3S_JWT_PUBLIC_KEY_PATH'

$privateKey = Get-RequiredFileContent -Path $privateKeyPath -Description 'JWT private key'
$publicKey = Get-RequiredFileContent -Path $publicKeyPath -Description 'JWT public key'

$certificatePath = Join-Path $repo 'nginx\certs\localhost.crt'
$certificateKeyPath = Join-Path $repo 'nginx\certs\localhost.key'
Get-RequiredFileContent -Path $certificatePath -Description 'Nginx TLS certificate' | Out-Null
Get-RequiredFileContent -Path $certificateKeyPath -Description 'Nginx TLS private key' | Out-Null

Assert-CommandAvailable -Name 'kubectl'
Assert-RancherDesktopContext
Assert-KubernetesApiReachable

$namespaceYaml = Invoke-KubectlYaml -Arguments @('create', 'namespace', 'flashsale', '--dry-run=client', '-o', 'yaml') -Operation 'rendering the namespace'
Apply-KubectlYaml -Yaml $namespaceYaml -Operation 'applying the namespace'

$secretYaml = Invoke-KubectlYaml -Arguments @(
    'create', 'secret', 'generic', 'flashsale-secrets', '--namespace', 'flashsale',
    "--from-literal=POSTGRES_PASSWORD=$postgresPassword",
    "--from-literal=SPRING_RABBITMQ_PASSWORD=$rabbitMqPassword",
    "--from-literal=JWT_PRIVATE_KEY=$privateKey",
    "--from-literal=JWT_PUBLIC_KEY=$publicKey",
    '--dry-run=client', '-o', 'yaml'
) -Operation 'rendering runtime secrets'
Apply-KubectlYaml -Yaml $secretYaml -Operation 'applying runtime secrets'

$tlsYaml = Invoke-KubectlYaml -Arguments @(
    'create', 'secret', 'tls', 'flashsale-local-tls', '--namespace', 'flashsale',
    "--cert=$certificatePath", "--key=$certificateKeyPath", '--dry-run=client', '-o', 'yaml'
) -Operation 'rendering the TLS secret'
Apply-KubectlYaml -Yaml $tlsYaml -Operation 'applying the TLS secret'

& kubectl apply -k (Join-Path $repo 'k8s\base')
if ($LASTEXITCODE -ne 0) {
    throw 'kubectl failed while applying k8s/base.'
}
