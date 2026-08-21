$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$base = Join-Path $repo 'k8s\base'
$required = @('namespace.yaml','config.yaml','data.yaml','support.yaml','application.yaml','gateway.yaml','kustomization.yaml')

foreach ($name in $required) {
    $path = Join-Path $base $name
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing manifest: $name" }
}

$all = ($required | ForEach-Object { Get-Content -Raw (Join-Path $base $_) }) -join "`n"
if ($all -match 'JWT_PRIVATE_KEY:\s*[^\r\n{]') { throw 'A JWT private key appears inline' }
if ($all -match 'POSTGRES_PASSWORD:\s*flashsale') { throw 'Default database password appears inline' }
if ($all -notmatch 'replicas:\s*1') { throw 'Baseline must declare one replica' }
if ($all -notmatch 'readinessProbe:') { throw 'No readiness probe found' }
if ($all -notmatch 'livenessProbe:') { throw 'No liveness probe found' }
if ($all -notmatch '(persistentVolumeClaim|volumeClaimTemplates)') { throw 'No persistent volume claim found' }
if ($all -notmatch 'imagePullPolicy:\s*Never') { throw 'Local baseline must not pull unpublished images' }

kubectl kustomize (Join-Path $repo 'k8s\base') | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'kubectl kustomize validation failed' }
Write-Host 'PASS: Kubernetes manifest contract'
