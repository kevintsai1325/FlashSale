$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$base = Join-Path $repo 'k8s\base'
$namespace = 'flashsale'
. (Join-Path $PSScriptRoot 'k8s-preflight.ps1')

function Get-EnvironmentValue {
    param([string]$Name, [bool]$Required = $false)
    $value = [Environment]::GetEnvironmentVariable($Name, 'Process')
    if ($Required -and [String]::IsNullOrWhiteSpace($value)) { throw "Required environment variable is missing or empty: $Name" }
    return $value
}

function Get-RequiredFileContent {
    param([string]$Path, [string]$Description)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Description was not found: $Path" }
    $content = Get-Content -LiteralPath $Path -Raw
    if ([String]::IsNullOrWhiteSpace($content)) { throw "$Description is empty: $Path" }
    return $content
}

function ConvertTo-Base64Text {
    param([string]$Value)
    return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
}

function Apply-SecretJson {
    param([string]$Json, [string]$Description)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $Json | & kubectl --context rancher-desktop apply -f - 2>&1 | Out-Null
        $exitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    if ($exitCode -ne 0) { throw "kubectl failed while applying $Description from stdin." }
}

function Apply-Stage {
    param([string]$Stage)
    Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', 'apply', '-k', $base, '-l', "flashsale.dev/stage=$Stage") -Operation "applying the $Stage stage" | Out-Null
}

function Wait-ForRollout {
    param([string]$Resource)
    Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'rollout', 'status', $Resource, '--timeout=240s') -Operation "waiting for $Resource" | Out-Null
}

Assert-KubernetesPreflight -KubectlCommand 'kubectl' | Out-Null

$namespaceName = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', 'get', 'namespace', $namespace, '--ignore-not-found', '-o', 'name') -Operation 'checking the baseline namespace' | Out-String).Trim()
$existingSecret = $null
if (-not [String]::IsNullOrWhiteSpace($namespaceName)) {
    $existingSecretText = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'secret', 'flashsale-secrets', '--ignore-not-found', '-o', 'json') -Operation 'checking stored credentials' | Out-String).Trim()
    if (-not [String]::IsNullOrWhiteSpace($existingSecretText)) { $existingSecret = $existingSecretText | ConvertFrom-Json }
}

$postgresInput = Get-EnvironmentValue -Name 'FL_K3S_POSTGRES_PASSWORD'
$rabbitInput = Get-EnvironmentValue -Name 'FL_K3S_RABBITMQ_PASSWORD'
if ($null -ne $existingSecret) {
    $postgresProperty = $existingSecret.data.PSObject.Properties['POSTGRES_PASSWORD']
    $rabbitProperty = $existingSecret.data.PSObject.Properties['SPRING_RABBITMQ_PASSWORD']
    if ($null -eq $postgresProperty -or $null -eq $rabbitProperty) { throw 'Existing flashsale-secrets is missing a stateful credential key; coordinated recovery is required.' }
    $postgresPassword = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string]$postgresProperty.Value))
    $rabbitPassword = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([string]$rabbitProperty.Value))
    if ((-not [String]::IsNullOrWhiteSpace($postgresInput)) -and ($postgresInput -cne $postgresPassword)) { throw 'PostgreSQL password changes require coordinated rotation and are outside this baseline.' }
    if ((-not [String]::IsNullOrWhiteSpace($rabbitInput)) -and ($rabbitInput -cne $rabbitPassword)) { throw 'RabbitMQ password changes require coordinated rotation and are outside this baseline.' }
}
else {
    if (-not [String]::IsNullOrWhiteSpace($namespaceName)) {
        $pvcs = @(Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'pvc', '-o', 'name') -Operation 'checking persistent state')
        if ($pvcs.Count -gt 0) { throw 'PVCs exist but flashsale-secrets is missing. Restore the retained Secret before deploying; do not invent replacement stateful credentials.' }
    }
    $postgresPassword = Get-EnvironmentValue -Name 'FL_K3S_POSTGRES_PASSWORD' -Required $true
    $rabbitPassword = Get-EnvironmentValue -Name 'FL_K3S_RABBITMQ_PASSWORD' -Required $true
}

$privateKeyPath = Get-EnvironmentValue -Name 'FL_K3S_JWT_PRIVATE_KEY_PATH' -Required $true
$publicKeyPath = Get-EnvironmentValue -Name 'FL_K3S_JWT_PUBLIC_KEY_PATH' -Required $true
$privateKey = Get-RequiredFileContent -Path $privateKeyPath -Description 'JWT private key'
$publicKey = Get-RequiredFileContent -Path $publicKeyPath -Description 'JWT public key'
$certificatePath = Join-Path $repo 'nginx\certs\localhost.crt'
$certificateKeyPath = Join-Path $repo 'nginx\certs\localhost.key'
Get-RequiredFileContent -Path $certificatePath -Description 'Nginx TLS certificate' | Out-Null
Get-RequiredFileContent -Path $certificateKeyPath -Description 'Nginx TLS private key' | Out-Null

$runtimeSecret = [ordered]@{
    apiVersion = 'v1'; kind = 'Secret'; type = 'Opaque'
    metadata = [ordered]@{ name = 'flashsale-secrets'; namespace = $namespace }
    data = [ordered]@{
        POSTGRES_PASSWORD = ConvertTo-Base64Text $postgresPassword
        SPRING_RABBITMQ_PASSWORD = ConvertTo-Base64Text $rabbitPassword
        JWT_PRIVATE_KEY = ConvertTo-Base64Text $privateKey
        JWT_PUBLIC_KEY = ConvertTo-Base64Text $publicKey
    }
} | ConvertTo-Json -Depth 5
$tlsSecret = [ordered]@{
    apiVersion = 'v1'; kind = 'Secret'; type = 'kubernetes.io/tls'
    metadata = [ordered]@{ name = 'flashsale-local-tls'; namespace = $namespace }
    data = [ordered]@{
        'tls.crt' = [Convert]::ToBase64String([IO.File]::ReadAllBytes($certificatePath))
        'tls.key' = [Convert]::ToBase64String([IO.File]::ReadAllBytes($certificateKeyPath))
    }
} | ConvertTo-Json -Depth 5

Apply-Stage -Stage 'bootstrap'
# 這一步的目的是讓 API server 驗證算繪出來的 manifest 結構，不是要接管欄位所有權。
# 必須帶 --force-conflicts：本腳本實際套用時用的是 client-side apply（Apply-Stage），
# 於是資源的欄位由 kubectl-client-side-apply 持有；server-side apply 在第二次以後的部署
# 會因為欄位所有權而衝突（例如 StatefulSet 的 .spec.volumeClaimTemplates）。
# 這是 dry-run，--force-conflicts 不會寫入任何東西，只是讓驗證能在既有資源上完成。
# 第一次部署時資源還不存在，所以這個問題直到重新部署才會浮現。
Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', 'apply', '--server-side', '--force-conflicts', '--dry-run=server', '-k', $base) -Operation 'server-side schema dry-run of the rendered baseline' | Out-Null
Apply-Stage -Stage 'foundation'
Apply-SecretJson -Json $runtimeSecret -Description 'runtime secrets'
Apply-SecretJson -Json $tlsSecret -Description 'the TLS secret'

Apply-Stage -Stage 'dependency'
foreach ($resource in @('statefulset/postgres', 'statefulset/postgres-purchase', 'statefulset/postgres-analytics', 'statefulset/postgres-order', 'statefulset/redis', 'statefulset/rabbitmq', 'statefulset/kafka', 'deployment/mailpit', 'deployment/zipkin')) { Wait-ForRollout $resource }

Apply-Stage -Stage 'application'
foreach ($name in @('backend', 'purchase-service', 'order-service', 'analytics-service', 'flink-jobmanager', 'flink-taskmanager', 'frontend', 'nginx')) {
    Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'rollout', 'restart', "deployment/$name") -Operation "restarting deployment/$name for local image or mounted TLS activation" | Out-Null
}
foreach ($name in @('backend', 'purchase-service', 'order-service', 'analytics-service', 'flink-jobmanager', 'flink-taskmanager', 'frontend', 'nginx')) { Wait-ForRollout "deployment/$name" }

foreach ($name in @('backend', 'purchase-service', 'order-service', 'analytics-service', 'flink-jobmanager', 'flink-taskmanager', 'frontend', 'nginx')) {
    # 期望值取自 Deployment 宣告的副本數，不寫死成 1。這個檢查的目的是證明「跑起來的是本機建置的
    # 映像」，不是把工作負載釘在單一 Pod 上；水平擴展本來就是跑在 Kubernetes 上的理由。
    $expected = [int](Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'deployment', $name, '-o', 'jsonpath={.spec.replicas}') -Operation "reading the desired replica count for $name" | Out-String).Trim()
    $podJson = (Invoke-KubectlChecked -Arguments @('--context', 'rancher-desktop', '-n', $namespace, 'get', 'pods', '-l', "app=$name", '-o', 'json') -Operation "reading $name image identity" | Out-String) | ConvertFrom-Json
    # rollout status 回來時，被取代的舊 Pod 可能還在 Terminating。它已經標記刪除，
    # 不算在「這次 rollout 的結果」裡，否則這個檢查會隨機失敗。
    $pods = @($podJson.items | Where-Object { $null -eq $_.metadata.PSObject.Properties['deletionTimestamp'] })
    if ($pods.Count -ne $expected) { throw "Expected $expected $name Pod(s) after rollout, got $($pods.Count)." }
    foreach ($pod in $pods) {
        $container = $pod.spec.containers[0]
        $status = $pod.status.containerStatuses[0]
        if ($container.image -ne "flashsale-$name`:local" -or [String]::IsNullOrWhiteSpace([string]$status.imageID)) { throw "$name Pod $($pod.metadata.name) did not report the expected local image and a resolved image ID." }
        Write-Host "Activated $name Pod $($pod.metadata.name) image ID: $($status.imageID)"
    }
}
