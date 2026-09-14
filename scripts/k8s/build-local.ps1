$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
. (Join-Path $PSScriptRoot 'k8s-preflight.ps1')

Assert-KubernetesPreflight -KubectlCommand 'kubectl' | Out-Null

function Test-DaemonReachable {
    # 探測 daemon 是否可用。原生命令寫 stderr 時，Windows PowerShell 5.1 會把每行包成
    # ErrorRecord；在 $ErrorActionPreference = 'Stop' 下這會直接拋錯，讓「探測失敗就換下一個」
    # 的邏輯失效。探測期間改為 Continue，只看 $LASTEXITCODE。
    param([string]$Command, [string[]]$ProbeArguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Command @ProbeArguments 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    }
    finally { $ErrorActionPreference = $previous }
}

function Resolve-ImageBuilder {
    # 以叢集實際回報的 runtime 為準，不假設某個容器引擎。
    # k3s 可能以 --docker 啟動（runtime 為 docker://），也可能使用 containerd（containerd://）。
    # 映像必須建進 kubelet 真正查找的那個 image store，imagePullPolicy: Never 才有東西可用。
    $runtime = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', 'get', 'nodes', '-o', 'jsonpath={.items[0].status.nodeInfo.containerRuntimeVersion}') -Operation 'reading the cluster container runtime' | Out-String).Trim()

    if ($runtime -like 'containerd://*') {
        $nerdctl = Get-Command nerdctl -ErrorAction SilentlyContinue
        if ($null -eq $nerdctl) { throw 'Cluster runtime is containerd but nerdctl is not on PATH. Put Rancher Desktop nerdctl on PATH.' }
        return [pscustomobject]@{ Kind = 'nerdctl'; Command = $nerdctl.Source }
    }

    if ($runtime -like 'docker://*') {
        # 探測 daemon 是否可用。原生命令寫 stderr 時，Windows PowerShell 5.1 會把每行包成
        # ErrorRecord；在 $ErrorActionPreference = 'Stop' 下這會直接拋錯，讓「探測失敗就換下一個」
        # 的邏輯失效。探測期間改為 Continue，只看 $LASTEXITCODE。
        $docker = Get-Command docker -ErrorAction SilentlyContinue
        if (($null -ne $docker) -and (Test-DaemonReachable -Command $docker.Source -ProbeArguments @('info'))) {
            return [pscustomobject]@{ Kind = 'docker'; Command = $docker.Source }
        }
        # Windows 端的 docker named pipe 可能不通（Hyper-V socket timeout），但 Rancher Desktop 的
        # dockerd 實際跑在 rancher-desktop WSL distro 內。改由該處呼叫，走的是同一個 daemon。
        $wsl = Get-Command wsl -ErrorAction SilentlyContinue
        if (($null -ne $wsl) -and (Test-DaemonReachable -Command $wsl.Source -ProbeArguments @('-d', 'rancher-desktop', '-e', 'docker', 'info'))) {
            return [pscustomobject]@{ Kind = 'wsl-docker'; Command = $wsl.Source }
        }
        throw 'Cluster runtime is docker but no reachable docker daemon was found on the Windows named pipe or in the rancher-desktop WSL distro.'
    }

    throw "Unsupported cluster container runtime: $runtime"
}

function ConvertTo-WslPath {
    param([string]$WindowsPath)
    $full = (Resolve-Path -LiteralPath $WindowsPath).Path
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    $rest = $full.Substring(2).Replace('\', '/')
    return "/mnt/$drive$rest"
}

function Invoke-ImageBuild {
    param([object]$Builder, [string]$Tag, [string]$ContextPath)
    switch ($Builder.Kind) {
        'nerdctl'    { & $Builder.Command --namespace k8s.io build --tag $Tag $ContextPath }
        'docker'     { & $Builder.Command build --tag $Tag $ContextPath }
        'wsl-docker' {
            # 在 VM 內用 BuildKit 建置時，解析基底映像會失敗：
            #   error getting credentials - err: fork/exec .../docker-credential-secretservice: no such file
            # BuildKit 會去找一個這個環境沒有的 credential helper，即使 DOCKER_CONFIG 指向一份
            # 空設定也一樣。本專案只拉公開映像、不推送任何映像，不需要任何憑證。
            # 傳統建置器（DOCKER_BUILDKIT=0）走不同的驗證路徑，實測可以正常拉取。
            $shell = "DOCKER_BUILDKIT=0 docker build --tag $Tag $(ConvertTo-WslPath $ContextPath)"
            & $Builder.Command -d rancher-desktop -e sh -c $shell
        }
        default      { throw "Unknown image builder kind: $($Builder.Kind)" }
    }
    if ($LASTEXITCODE -ne 0) { throw "Image build failed: $Tag" }
}

function Get-BuiltImages {
    param([object]$Builder)
    switch ($Builder.Kind) {
        'nerdctl'    { return & $Builder.Command --namespace k8s.io images }
        'docker'     { return & $Builder.Command images }
        'wsl-docker' { return & $Builder.Command -d rancher-desktop -e docker images }
        default      { throw "Unknown image builder kind: $($Builder.Kind)" }
    }
}

$builder = Resolve-ImageBuilder
Write-Host "Image builder: $($builder.Kind)"

$builds = @(
    @{ Tag = 'flashsale-backend:local'; Path = 'backend' },
    @{ Tag = 'flashsale-purchase-service:local'; Path = 'purchase-service' },
    @{ Tag = 'flashsale-frontend:local'; Path = 'frontend' },
    @{ Tag = 'flashsale-nginx:local'; Path = 'nginx' }
)
foreach ($build in $builds) {
    $contextPath = Join-Path $repo $build.Path
    if (-not (Test-Path -LiteralPath (Join-Path $contextPath 'Dockerfile') -PathType Leaf)) { throw "Dockerfile not found for image build: $($build.Tag)" }
    Invoke-ImageBuild -Builder $builder -Tag $build.Tag -ContextPath $contextPath
}

$images = Get-BuiltImages -Builder $builder
if ($LASTEXITCODE -ne 0) { throw 'Unable to list images from the selected image builder.' }
$images | Select-String 'flashsale-(backend|purchase-service|frontend|nginx)'
