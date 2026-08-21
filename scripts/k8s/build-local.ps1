$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'k8s-preflight.ps1')

Assert-KubernetesPreflight -KubectlCommand 'kubectl' | Out-Null
if ($null -eq (Get-Command nerdctl -ErrorAction SilentlyContinue)) {
    throw 'nerdctl is required for the containerd k8s.io image namespace. Configure Rancher Desktop to use containerd and put Rancher Desktop nerdctl on PATH.'
}

$builds = @(
    @{ Tag = 'flashsale-backend:local'; Path = 'backend' },
    @{ Tag = 'flashsale-frontend:local'; Path = 'frontend' },
    @{ Tag = 'flashsale-nginx:local'; Path = 'nginx' }
)
foreach ($build in $builds) {
    $contextPath = Join-Path $repo $build.Path
    if (-not (Test-Path -LiteralPath (Join-Path $contextPath 'Dockerfile') -PathType Leaf)) { throw "Dockerfile not found for image build: $($build.Tag)" }
    & nerdctl --namespace k8s.io build --tag $build.Tag $contextPath
    if ($LASTEXITCODE -ne 0) { throw "Image build failed: $($build.Tag)" }
}

$images = & nerdctl --namespace k8s.io images
if ($LASTEXITCODE -ne 0) { throw 'Unable to list images from nerdctl containerd namespace k8s.io.' }
$images | Select-String 'flashsale-(backend|frontend|nginx)'
