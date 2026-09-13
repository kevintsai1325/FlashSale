$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$base = Join-Path $repo 'k8s\base'
$requirementsPath = Join-Path $PSScriptRoot 'requirements-k8s.txt'

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

foreach ($command in @('kubectl', 'python')) {
    Assert-True ($null -ne (Get-Command $command -ErrorAction SilentlyContinue)) "$command is required for the offline manifest contract test."
}
Assert-True (Test-Path -LiteralPath $requirementsPath -PathType Leaf) "Pinned test requirements are missing: $requirementsPath"
$pinnedRequirement = (Get-Content -LiteralPath $requirementsPath | Where-Object { $_ -match '^PyYAML==' } | Select-Object -First 1)
Assert-True (-not [String]::IsNullOrWhiteSpace($pinnedRequirement)) 'requirements-k8s.txt must pin PyYAML with ==.'
$pinnedPyYamlVersion = $pinnedRequirement.Substring('PyYAML=='.Length).Trim()
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $pyYamlVersion = (& python -c 'import yaml; print(yaml.__version__)' 2>&1 | Out-String).Trim()
    $pyYamlExitCode = $LASTEXITCODE
}
finally { $ErrorActionPreference = $previousPreference }
if ($pyYamlExitCode -ne 0) { throw "PyYAML is required. Install the pinned dependency with: python -m pip install -r scripts/tests/requirements-k8s.txt" }
Assert-True ($pyYamlVersion -eq $pinnedPyYamlVersion) "PyYAML $pyYamlVersion is installed; version $pinnedPyYamlVersion is required. Run: python -m pip install -r scripts/tests/requirements-k8s.txt"

$renderedText = (& kubectl kustomize $base | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'kubectl kustomize validation failed.' }

$yamlToJson = @'
import json, sys, yaml
documents = [document for document in yaml.safe_load_all(sys.stdin.read()) if document]
json.dump(documents, sys.stdout)
'@
$renderedJson = ($renderedText | & python -c $yamlToJson | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'PyYAML could not parse the rendered Kubernetes resources.' }
$parsedResources = $renderedJson | ConvertFrom-Json
$resources = @()
for ($index = 0; $index -lt $parsedResources.Count; $index++) {
    $resources += $parsedResources[$index]
}
Assert-True ($resources.Count -eq 21) "Expected exactly 21 rendered resources, got $($resources.Count)."

$secrets = @($resources | Where-Object { $_.kind -eq 'Secret' })
Assert-True ($secrets.Count -eq 0) 'Rendered resources must not contain Secret objects or values.'
Assert-True ($renderedText -notmatch 'test-postgres-password|test-rabbitmq-password|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY') 'Rendered resources contain a secret value.'

$namespaces = @($resources | Where-Object { $_.kind -eq 'Namespace' })
Assert-True (($namespaces.Count -eq 1) -and ($namespaces[0].metadata.name -eq 'flashsale')) 'The rendered baseline must contain exactly the flashsale Namespace.'
$namespacedResources = @($resources | Where-Object { $_.kind -ne 'Namespace' })
foreach ($resource in $namespacedResources) {
    Assert-True ((Get-PropertyValue $resource.metadata 'namespace') -eq 'flashsale') "$($resource.kind)/$($resource.metadata.name) must target flashsale."
}

$expectedStages = @{
    bootstrap = @('Namespace/flashsale')
    foundation = @('ConfigMap/flashsale-config')
    dependency = @(
        'Service/postgres-headless', 'Service/postgres', 'StatefulSet/postgres',
        'Service/redis-headless', 'Service/redis', 'StatefulSet/redis',
        'Service/rabbitmq-headless', 'Service/rabbitmq', 'StatefulSet/rabbitmq',
        'Deployment/mailpit', 'Service/mailpit', 'Deployment/zipkin', 'Service/zipkin'
    )
    application = @('Deployment/backend', 'Service/backend', 'Deployment/frontend', 'Service/frontend', 'Deployment/nginx', 'Service/nginx')
}
$allowedStages = @($expectedStages.Keys)
foreach ($resource in $resources) {
    $labels = Get-PropertyValue $resource.metadata 'labels'
    $stageProperties = @($labels.PSObject.Properties | Where-Object { $_.Name -eq 'flashsale.dev/stage' })
    Assert-True ($stageProperties.Count -eq 1) "$($resource.kind)/$($resource.metadata.name) must have exactly one flashsale.dev/stage label."
    Assert-True ($stageProperties[0].Value -in $allowedStages) "$($resource.kind)/$($resource.metadata.name) has an unsupported stage label: $($stageProperties[0].Value)"
}
foreach ($stage in $expectedStages.Keys) {
    $actual = @($resources | Where-Object { (Get-PropertyValue (Get-PropertyValue $_.metadata 'labels') 'flashsale.dev/stage') -eq $stage } | ForEach-Object { "$($_.kind)/$($_.metadata.name)" } | Sort-Object)
    $expected = @($expectedStages[$stage] | Sort-Object)
    Assert-True (($actual -join ',') -eq ($expected -join ',')) "$stage stage resources do not match the staged deployment contract."
}

$expectedWorkloads = @('postgres', 'redis', 'rabbitmq', 'mailpit', 'zipkin', 'backend', 'frontend', 'nginx')
$workloads = @($resources | Where-Object { $_.kind -in @('Deployment', 'StatefulSet') })
Assert-True ($workloads.Count -eq 8) "Expected exactly eight workloads, got $($workloads.Count)."
Assert-True ((@($workloads | ForEach-Object { $_.metadata.name } | Sort-Object) -join ',') -eq (($expectedWorkloads | Sort-Object) -join ',')) 'The rendered workload names do not match the baseline contract.'
foreach ($workload in $workloads) {
    $name = $workload.metadata.name
    Assert-True ($workload.spec.replicas -eq 1) "$name must declare exactly one replica."
    $containers = @($workload.spec.template.spec.containers)
    Assert-True ($containers.Count -eq 1) "$name must contain exactly one application container."
    Assert-True ($null -ne (Get-PropertyValue $containers[0] 'readinessProbe')) "$name must define a readiness probe."
    Assert-True ($null -ne (Get-PropertyValue $containers[0] 'livenessProbe')) "$name must define a liveness probe."
}

foreach ($name in @('postgres', 'redis', 'rabbitmq', 'zipkin', 'backend')) {
    $workload = @($workloads | Where-Object { $_.metadata.name -eq $name })[0]
    $startupProbe = Get-PropertyValue $workload.spec.template.spec.containers[0] 'startupProbe'
    Assert-True ($null -ne $startupProbe) "$name must define a startup probe."
    Assert-True ([int]$startupProbe.timeoutSeconds -gt 0) "$name startup probe must define a positive timeoutSeconds."
}

$statefulSets = @($workloads | Where-Object { $_.kind -eq 'StatefulSet' })
Assert-True ($statefulSets.Count -eq 3) 'Expected exactly three StatefulSets.'
foreach ($statefulSet in $statefulSets) {
    $name = $statefulSet.metadata.name
    Assert-True ($statefulSet.spec.serviceName -eq "$name-headless") "$name must use its headless governing Service."
    Assert-True (@($statefulSet.spec.volumeClaimTemplates).Count -eq 1) "$name must define exactly one volume claim template."
}

$services = @($resources | Where-Object { $_.kind -eq 'Service' })
foreach ($name in @('postgres', 'redis', 'rabbitmq')) {
    $clientService = @($services | Where-Object { $_.metadata.name -eq $name })
    $headlessService = @($services | Where-Object { $_.metadata.name -eq "$name-headless" })
    Assert-True ($clientService.Count -eq 1) "$name must retain one client-facing ClusterIP Service."
    $clientType = Get-PropertyValue $clientService[0].spec 'type'
    Assert-True (($null -eq $clientType) -or ($clientType -eq 'ClusterIP')) "$name client Service must use the default or explicit ClusterIP type."
    Assert-True (($headlessService.Count -eq 1) -and ($headlessService[0].spec.clusterIP -eq 'None')) "$name must have one clusterIP None headless Service."
}

$localImages = @{ backend = 'flashsale-backend:local'; frontend = 'flashsale-frontend:local'; nginx = 'flashsale-nginx:local' }
foreach ($name in $localImages.Keys) {
    $workload = @($workloads | Where-Object { $_.metadata.name -eq $name })[0]
    $container = $workload.spec.template.spec.containers[0]
    Assert-True ($container.image -eq $localImages[$name]) "$name must use its local image tag."
    Assert-True ($container.imagePullPolicy -eq 'Never') "$name must use imagePullPolicy Never."
}

$postgres = @($workloads | Where-Object { $_.metadata.name -eq 'postgres' })[0]
$rabbitmq = @($workloads | Where-Object { $_.metadata.name -eq 'rabbitmq' })[0]
$backend = @($workloads | Where-Object { $_.metadata.name -eq 'backend' })[0]
$nginx = @($workloads | Where-Object { $_.metadata.name -eq 'nginx' })[0]
$mailpit = @($workloads | Where-Object { $_.metadata.name -eq 'mailpit' })[0]
Assert-True ($mailpit.spec.template.spec.containers[0].image -eq 'axllent/mailpit:v1.27.9') 'Mailpit must use the pinned v1.27.9 image.'
Assert-True ($postgres.spec.template.spec.containers[0].env[0].valueFrom.secretKeyRef.name -eq 'flashsale-secrets') 'PostgreSQL must read its password from flashsale-secrets.'
Assert-True ($rabbitmq.spec.template.spec.containers[0].env[1].valueFrom.secretKeyRef.name -eq 'flashsale-secrets') 'RabbitMQ must read its password from flashsale-secrets.'
$backendSecretRefs = @($backend.spec.template.spec.containers[0].env | ForEach-Object { $_.valueFrom.secretKeyRef.name })
Assert-True (($backendSecretRefs.Count -eq 4) -and (@($backendSecretRefs | Where-Object { $_ -ne 'flashsale-secrets' }).Count -eq 0)) 'Backend must source all four sensitive values from flashsale-secrets.'
Assert-True ($nginx.spec.template.spec.volumes[0].secret.secretName -eq 'flashsale-local-tls') 'Nginx must mount flashsale-local-tls.'

# Backend is the workload that scales horizontally, so its update behaviour must be declared
# rather than inherited. The Kubernetes default of 25% maxUnavailable would take a replica out
# of service during every rollout, which is exactly what the scale-out is meant to prevent.
$backendStrategy = Get-PropertyValue $backend.spec 'strategy'
Assert-True ((Get-PropertyValue $backendStrategy 'type') -eq 'RollingUpdate') 'Backend must declare an explicit RollingUpdate strategy.'
$backendRollingUpdate = Get-PropertyValue $backendStrategy 'rollingUpdate'
Assert-True ([string](Get-PropertyValue $backendRollingUpdate 'maxUnavailable') -eq '0') 'Backend rolling update must keep every existing replica available (maxUnavailable 0).'
Assert-True ([string](Get-PropertyValue $backendRollingUpdate 'maxSurge') -eq '1') 'Backend rolling update must add at most one surge Pod at a time.'

Write-Host 'PASS: Kubernetes rendered-resource contract (21 resources, exact stages, 8 workloads, probes, persistence, headless Services, Secret refs, namespace, local images, and backend rollout strategy).'
