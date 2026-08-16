#Requires -Version 5.1
<#
.SYNOPSIS
    Runs the FlashSale load benchmark inside the isolated `flashsale-benchmark`
    Compose project and writes one result document.

.DESCRIPTION
    Full mode runs the contention matrix (30 VUs/10 stock, 100/30, 300/100, five
    times each) and then one 10/s x 10m soak over 6,000 unique buyers. Smoke mode
    runs a single tiny contention run and is only there to prove the harness works.

    Every run is reset -> seeded -> prepared -> measured -> probed, and every run is
    recorded, including runs that fail: a failed run keeps its error detail and is
    counted in summary.failedRuns so nothing can be silently dropped. The document
    is checked by verify-results.mjs, which re-derives the invariants from the
    numbers captured here.

    The script refuses to do anything unless it can prove it is talking to the
    isolated project: the project name must be the literal `flashsale-benchmark`,
    Docker/Compose environment overrides must be absent, and the running containers
    must carry the Compose labels of this file. Only then does it truncate a
    database, flush a Redis, or run `down -v`.

.PARAMETER Mode
    `full` (15 contention runs + soak) or `smoke` (one small contention run).

.PARAMETER ProjectName
    Must be `flashsale-benchmark`. Exists so the guard is visible, not so it can be
    changed.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode full

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File load-tests/benchmark/collect.ps1 -Mode smoke -SmokeVus 3 -SmokeStock 1
#>
[CmdletBinding()]
param(
    [ValidateSet('full', 'smoke')]
    [string]$Mode = 'full',

    [string]$ProjectName = 'flashsale-benchmark',

    [string]$OutDir = '',

    [int]$SmokeVus = 3,

    [int]$SmokeStock = 1,

    [switch]$KeepStack
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# The only project name this script will ever operate on. Compare with -cne
# (case-sensitive) so no casing trick can slip past the guard.
$BenchmarkProject = 'flashsale-benchmark'

$ScriptDir = $PSScriptRoot
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$ComposeFile = Join-Path $ScriptDir 'compose.benchmark.yaml'
$FixturesFile = Join-Path $ScriptDir 'fixtures.sql'
$PrepareScript = Join-Path $ScriptDir 'prepare.js'
$PurchaseScript = Join-Path $ScriptDir 'purchase-load.js'
$SoakScript = Join-Path $ScriptDir 'soak.js'
$VerifierScript = Join-Path $ScriptDir 'verify-results.mjs'
$EnvFile = Join-Path $RepoRoot '.env'

$BackendPort = 18080
if ($env:BENCHMARK_BACKEND_PORT) {
    $BackendPort = [int]$env:BENCHMARK_BACKEND_PORT
}
$BaseUrl = "http://127.0.0.1:$BackendPort"

# The contention matrix. Each profile is repeated $RepeatsPerProfile times so Task 5
# can report spread rather than a single lucky run.
$ContentionProfiles = @(
    @{ Vus = 30;  Stock = 10  },
    @{ Vus = 100; Stock = 30  },
    @{ Vus = 300; Stock = 100 }
)
$RepeatsPerProfile = 5

# Soak contract - kept in lockstep with soak.js and verify-results.mjs.
$SoakUsers = 6000
$SoakScenario = [ordered]@{
    executor        = 'constant-arrival-rate'
    rate            = 10
    timeUnit        = '1s'
    duration        = '10m'
    preAllocatedVUs = 50
    maxVUs          = 200
}

$RabbitQueues = @(
    'order.create.queue',
    'order.create.queue.dlq',
    'stock.release.queue',
    'stock.release.queue.dlq'
)

# ---------------------------------------------------------------------------
# Isolation guards
# ---------------------------------------------------------------------------

function Assert-BenchmarkIsolation {
    if ($ProjectName -cne $BenchmarkProject) {
        throw "refusing to run: project name must be '$BenchmarkProject', got '$ProjectName'"
    }
    if ($env:COMPOSE_PROJECT_NAME -and $env:COMPOSE_PROJECT_NAME -cne $BenchmarkProject) {
        throw "refusing to run: COMPOSE_PROJECT_NAME is '$($env:COMPOSE_PROJECT_NAME)'; unset it so the benchmark cannot retarget another stack"
    }
    if ($env:COMPOSE_FILE) {
        throw "refusing to run: COMPOSE_FILE is set ('$($env:COMPOSE_FILE)'); unset it so only compose.benchmark.yaml is used"
    }
    if ($env:DOCKER_HOST) {
        throw "refusing to run: DOCKER_HOST is set ('$($env:DOCKER_HOST)'); the benchmark only runs against the local Docker engine"
    }
    if ($env:DOCKER_CONTEXT -and $env:DOCKER_CONTEXT -notin @('default', 'desktop-linux')) {
        throw "refusing to run: DOCKER_CONTEXT is '$($env:DOCKER_CONTEXT)'; the benchmark only runs against the local Docker engine"
    }
    foreach ($required in @($ComposeFile, $FixturesFile, $PrepareScript, $PurchaseScript, $SoakScript)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "refusing to run: missing harness file $required"
        }
    }
    if (-not (Test-Path -LiteralPath $EnvFile)) {
        throw "refusing to run: $EnvFile not found; the benchmark backend needs JWT_PRIVATE_KEY/JWT_PUBLIC_KEY exactly like the normal stack (see README.md)"
    }
    if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
        throw "refusing to run: k6 is not on PATH (see load-tests/benchmark/README.md, Prerequisites)"
    }
}

function Get-NormalizedPath {
    param([string]$Path)
    if (-not $Path) { return '' }
    return $Path.Replace('\', '/').TrimEnd('/').ToLowerInvariant()
}

# Proves the containers we are about to mutate really belong to this compose file
# and this project - the same fail-closed identity check scripts/demo-data.sh makes
# before it touches the demo stack.
function Assert-ComposeIdentity {
    $expectedConfig = Get-NormalizedPath (Resolve-Path -LiteralPath $ComposeFile).Path
    foreach ($service in @('postgres', 'redis', 'rabbitmq', 'backend')) {
        $containerId = (Invoke-Compose -Arguments @('ps', '-q', $service) -Capture | Select-Object -First 1)
        if (-not $containerId) {
            throw "refusing to continue: no container found for service '$service' in project '$BenchmarkProject'"
        }
        # Plain JSON rather than --format: Windows PowerShell 5.1 mangles the inner
        # double quotes of a Go template when it hands the argument to a native
        # command, and docker then fails with `function "com" not defined`.
        $inspectOutput = & docker inspect $containerId
        if ($LASTEXITCODE -ne 0) {
            throw "refusing to continue: docker inspect failed for $service ($containerId)"
        }
        $inspected = (($inspectOutput -join "`n") | ConvertFrom-Json)
        $labels = $inspected[0].Config.Labels
        $project = [string]$labels.'com.docker.compose.project'
        $configFiles = [string]$labels.'com.docker.compose.project.config_files'
        if ($project -cne $BenchmarkProject) {
            throw "refusing to continue: container $containerId belongs to Compose project '$project', not '$BenchmarkProject'"
        }
        if ((Get-NormalizedPath $configFiles) -ne $expectedConfig) {
            throw "refusing to continue: container $containerId was created from '$configFiles', not '$ComposeFile'"
        }
    }
    Write-Host "isolation verified: project '$BenchmarkProject' from $ComposeFile"
}

# ---------------------------------------------------------------------------
# Compose helpers
# ---------------------------------------------------------------------------

function Invoke-Compose {
    param(
        [string[]]$Arguments,
        [switch]$Capture,
        [switch]$IgnoreExitCode
    )
    $all = @('compose', '-p', $BenchmarkProject, '--project-directory', $RepoRoot, '-f', $ComposeFile) + $Arguments
    if ($Capture) {
        $output = & docker @all
    }
    else {
        # Out-Host keeps docker's chatter off this function's output stream, so the
        # only thing a caller can receive back is what we deliberately return.
        & docker @all | Out-Host
        $output = $null
    }
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreExitCode) {
        throw "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
    }
    return $output
}

# Pipes text into a container's stdin (psql scripts, mostly).
function Invoke-ComposeStdin {
    param(
        [string]$Service,
        [string[]]$Command,
        [string]$StdIn,
        [switch]$IgnoreExitCode
    )
    $all = @('compose', '-p', $BenchmarkProject, '--project-directory', $RepoRoot, '-f', $ComposeFile, 'exec', '-T', $Service) + $Command
    $output = $StdIn | & docker @all
    if ($LASTEXITCODE -ne 0 -and -not $IgnoreExitCode) {
        throw "docker compose exec $Service $($Command -join ' ') failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Invoke-Psql {
    param([string]$Sql, [string[]]$ExtraArgs = @())
    $command = @('psql', '-U', 'flashsale', '-d', 'flashsale', '-v', 'ON_ERROR_STOP=1') + $ExtraArgs
    return Invoke-ComposeStdin -Service 'postgres' -Command $command -StdIn $Sql
}

function Wait-BackendReady {
    param([int]$TimeoutSeconds = 300)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -TimeoutSec 5
            if ($response.status -eq 'UP') {
                Write-Host "backend ready at $BaseUrl"
                return
            }
        }
        catch {
            # not up yet
        }
        Start-Sleep -Seconds 3
    }
    throw "backend did not become ready at $BaseUrl within $TimeoutSeconds seconds"
}

# ---------------------------------------------------------------------------
# Per-run data lifecycle
# ---------------------------------------------------------------------------

function Reset-BenchmarkData {
    param([int]$Stock)

    # Postgres: truncate + reseed. fixtures.sql is destructive by design; the
    # identity guard above is what makes it safe to pipe here.
    # Normalise to LF: psql treats a trailing CR on a `\set` meta-command as part of
    # the value, so a CRLF checkout would break the fixture in a confusing way.
    $sql = (Get-Content -Raw -LiteralPath $FixturesFile) -replace "`r`n", "`n"
    Invoke-Psql -Sql $sql -ExtraArgs @('-v', "stock=$Stock") | Out-Null

    # Redis: the reservation counter `stock:<flashSaleId>` is lazily seeded from
    # Postgres on first use, so a leftover key from the previous run would silently
    # cap (or inflate) this run's stock. This Redis only ever holds benchmark data.
    Invoke-Compose -Arguments @('exec', '-T', 'redis', 'redis-cli', 'FLUSHALL') | Out-Null

    # RabbitMQ: drop anything a previous failed run left queued so it cannot be
    # consumed into this run's order count.
    foreach ($queue in $RabbitQueues) {
        Invoke-Compose -Arguments @('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'purge_queue', $queue) -Capture -IgnoreExitCode | Out-Null
    }
}

function New-AdminToken {
    param([string]$RunId)
    $email = "bench-admin-$RunId@benchmark.local"
    $password = [guid]::NewGuid().ToString('N')
    $body = (@{ email = $email; password = $password } | ConvertTo-Json -Compress)
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30 | Out-Null
    }
    catch {
        # An existing account is fine; the UPDATE and login below still apply.
    }
    Invoke-Psql -Sql "UPDATE users SET role = 'ADMIN' WHERE email = '$email';" | Out-Null
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30
    return $login.accessToken
}

function Wait-ForDrain {
    param([int]$TimeoutSeconds = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $pending = (Invoke-Psql -Sql "SELECT count(*) FROM purchase_requests WHERE status = 'PENDING';" -ExtraArgs @('-t', '-A') | Select-Object -First 1)
        if ([int]$pending -eq 0) {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Get-DbInvariants {
    $sql = @"
SELECT json_build_object(
  'ordersCreated', (SELECT count(*) FROM orders),
  'succeededRequests', (SELECT count(*) FROM purchase_requests WHERE status = 'SUCCEEDED'),
  'soldOutRequests', (SELECT count(*) FROM purchase_requests WHERE status = 'SOLD_OUT'),
  'rejectedRequests', (SELECT count(*) FROM purchase_requests WHERE status = 'REJECTED'),
  'failedRequests', (SELECT count(*) FROM purchase_requests WHERE status = 'FAILED'),
  'residualPending', (SELECT count(*) FROM purchase_requests WHERE status = 'PENDING'),
  'duplicateOrderUsers', (SELECT count(*) FROM (SELECT user_id FROM orders GROUP BY user_id HAVING count(*) > 1) duplicates),
  'duplicateSucceededUsers', (SELECT count(*) FROM (SELECT user_id FROM purchase_requests WHERE status = 'SUCCEEDED' GROUP BY user_id HAVING count(*) > 1) duplicates),
  'orderItems', (SELECT count(*) FROM order_items),
  'ordersWithoutItems', (SELECT count(*) FROM orders o WHERE NOT EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id)),
  'unpublishedOutboxEvents', (SELECT count(*) FROM outbox_events WHERE published_at IS NULL),
  'inventory', COALESCE(
      (SELECT json_build_object('available', available_quantity, 'reserved', reserved_quantity, 'sold', sold_quantity)
       FROM inventory WHERE flash_sale_id = 1),
      json_build_object('available', -1, 'reserved', -1, 'sold', -1))
);
"@
    $line = (Invoke-Psql -Sql $sql -ExtraArgs @('-t', '-A') | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
    return ($line | ConvertFrom-Json)
}

function Get-QueueDepths {
    $raw = Invoke-Compose -Arguments @('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'list_queues', '--quiet', '--formatter', 'json', 'name', 'messages', 'messages_ready', 'messages_unacknowledged') -Capture -IgnoreExitCode
    $text = ($raw -join '')
    if (-not $text) {
        return @()
    }
    try {
        $parsed = $text | ConvertFrom-Json
    }
    catch {
        return @(@{ error = "unparseable rabbitmqctl output: $text" })
    }
    $queues = @()
    foreach ($queue in $parsed) {
        $queues += [ordered]@{
            name                    = $queue.name
            messages                = $queue.messages
            messagesReady           = $queue.messages_ready
            messagesUnacknowledged  = $queue.messages_unacknowledged
        }
    }
    return $queues
}

function Get-HealthSnapshot {
    $snapshot = [ordered]@{ readiness = 'UNKNOWN'; liveness = 'UNKNOWN'; overall = 'UNKNOWN' }
    foreach ($probe in @(@{ Key = 'readiness'; Path = '/actuator/health/readiness' },
                          @{ Key = 'liveness';  Path = '/actuator/health/liveness'  },
                          @{ Key = 'overall';   Path = '/actuator/health'           })) {
        try {
            $response = Invoke-RestMethod -Uri "$BaseUrl$($probe.Path)" -TimeoutSec 10
            $snapshot[$probe.Key] = $response.status
        }
        catch {
            $snapshot[$probe.Key] = "ERROR: $($_.Exception.Message)"
        }
    }
    return $snapshot
}

function Get-ActuatorMetrics {
    param([string]$Token)
    $names = @('purchase.reservation', 'purchase.reservation.latency', 'purchase.order.created')
    $metrics = [ordered]@{}
    foreach ($name in $names) {
        try {
            $response = Invoke-RestMethod -Uri "$BaseUrl/actuator/metrics/$name" -Headers @{ Authorization = "Bearer $Token" } -TimeoutSec 15
            $measurements = [ordered]@{}
            foreach ($measurement in $response.measurements) {
                $measurements[$measurement.statistic] = $measurement.value
            }
            $metrics[$name] = $measurements
        }
        catch {
            $metrics[$name] = "ERROR: $($_.Exception.Message)"
        }
    }
    return $metrics
}

# ---------------------------------------------------------------------------
# k6 invocation
# ---------------------------------------------------------------------------

function Invoke-K6 {
    param([string]$Script, [string[]]$EnvArgs)
    $arguments = @('run', $Script) + $EnvArgs
    # Out-Host, not the pipeline: otherwise k6's console output would be returned
    # alongside the exit code and the caller's `-ne 0` check would compare an array.
    & k6 @arguments | Out-Host
    return $LASTEXITCODE
}

function Read-K6Summary {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    return (Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json)
}

# ---------------------------------------------------------------------------
# Runs
# ---------------------------------------------------------------------------

function Invoke-BenchmarkRun {
    param(
        [string]$RunId,
        [ValidateSet('contention', 'soak')]
        [string]$Kind,
        [int]$Users,
        [int]$Stock,
        [string]$SessionDir
    )

    Write-Host ""
    Write-Host "=== $RunId ($Kind, users=$Users, stock=$Stock) ==="

    $run = [ordered]@{
        runId      = $RunId
        kind       = $Kind
        status     = 'valid'
        stock      = $Stock
        startedAt  = (Get-Date).ToUniversalTime().ToString('o')
        finishedAt = $null
        k6         = [ordered]@{ exitCode = -1; checksFailed = $null; summaryFile = $null }
        errors     = @()
    }
    if ($Kind -eq 'contention') {
        $run['vus'] = $Users
    }
    else {
        $run['users'] = $Users
        $run['scenario'] = $SoakScenario
    }

    $tokensFile = Join-Path $SessionDir "tokens-$RunId.json"
    $k6SummaryFile = Join-Path $SessionDir "k6-$RunId.json"
    # Record only the file name: results.json is a publishable artifact, so it must
    # never carry the absolute path of the machine that produced it. The file always
    # lives next to results.json in the same session directory.
    $run.k6.summaryFile = Split-Path -Leaf $k6SummaryFile

    try {
        Reset-BenchmarkData -Stock $Stock
        $adminToken = New-AdminToken -RunId $RunId

        # --- prepare: accounts and tokens, outside the measured window ---
        $benchPassword = [guid]::NewGuid().ToString('N')
        $prepareExit = Invoke-K6 -Script $PrepareScript -EnvArgs @(
            '-e', "USERS=$Users",
            '-e', "RUN_ID=$RunId",
            '-e', "BASE_URL=$BaseUrl",
            '-e', "BENCH_PASSWORD=$benchPassword",
            '-e', "TOKENS_OUT=$tokensFile"
        )
        if ($prepareExit -ne 0) {
            throw "prepare.js exited with code $prepareExit"
        }

        # --- measure ---
        if ($Kind -eq 'contention') {
            $exitCode = Invoke-K6 -Script $PurchaseScript -EnvArgs @(
                '-e', "VUS=$Users",
                '-e', "STOCK=$Stock",
                '-e', "RUN_ID=$RunId",
                '-e', "BASE_URL=$BaseUrl",
                '-e', "TOKENS_FILE=$tokensFile",
                '-e', "SUMMARY_OUT=$k6SummaryFile"
            )
        }
        else {
            $exitCode = Invoke-K6 -Script $SoakScript -EnvArgs @(
                '-e', "RUN_ID=$RunId",
                '-e', "STOCK=$Stock",
                '-e', "BASE_URL=$BaseUrl",
                '-e', "TOKENS_FILE=$tokensFile",
                '-e', "SUMMARY_OUT=$k6SummaryFile"
            )
        }
        $run.k6.exitCode = $exitCode
        if ($exitCode -ne 0) {
            # Recorded, never discarded: the run stays in the document as failed.
            $run.status = 'failed'
            $run.errors += "k6 exited with code $exitCode"
        }

        # --- observe: everything below is captured even for a failed run ---
        if (-not (Wait-ForDrain)) {
            $run.errors += 'purchase requests were still PENDING when the drain wait timed out'
            $run.status = 'failed'
        }

        $summary = Read-K6Summary -Path $k6SummaryFile
        if ($summary) {
            $run.k6.checksFailed = $summary.checksFailed
            $run['metrics'] = $summary.metrics
            $run['outcomes'] = $summary.outcomes
            if ($summary.startedAt) { $run.startedAt = $summary.startedAt }
        }
        else {
            $run.status = 'failed'
            $run.errors += "k6 wrote no summary to $($run.k6.summaryFile)"
        }

        $run['invariants'] = Get-DbInvariants
        $run['queues'] = Get-QueueDepths
        $run['health'] = Get-HealthSnapshot
        $run['actuator'] = Get-ActuatorMetrics -Token $adminToken
    }
    catch {
        $run.status = 'failed'
        $run.errors += $_.Exception.Message
        Write-Warning "$RunId failed: $($_.Exception.Message)"
    }

    $run.finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    return $run
}

# ---------------------------------------------------------------------------
# Environment metadata
# ---------------------------------------------------------------------------

function Get-CommandOutput {
    param([string]$Command, [string[]]$Arguments, [string]$Fallback = 'unavailable')
    try {
        $output = & $Command @Arguments
        if ($LASTEXITCODE -ne 0) { return $Fallback }
        return (($output -join ' ').Trim())
    }
    catch {
        return $Fallback
    }
}

function Get-EnvironmentMetadata {
    param([string]$StartedAt)

    $cpuModel = $env:PROCESSOR_IDENTIFIER
    $logicalCores = [int]$env:NUMBER_OF_PROCESSORS
    $memoryGb = 0
    $osName = "$([System.Environment]::OSVersion.VersionString)"
    try {
        $processor = Get-CimInstance -ClassName Win32_Processor | Select-Object -First 1
        if ($processor) {
            $cpuModel = $processor.Name
            $logicalCores = [int]$processor.NumberOfLogicalProcessors
        }
        $computer = Get-CimInstance -ClassName Win32_ComputerSystem
        if ($computer) {
            $memoryGb = [math]::Round($computer.TotalPhysicalMemory / 1GB, 1)
        }
        $os = Get-CimInstance -ClassName Win32_OperatingSystem
        if ($os) {
            $osName = "$($os.Caption) $($os.Version)"
        }
    }
    catch {
        Write-Warning "could not read full hardware metadata: $($_.Exception.Message)"
    }

    $gitStatus = Get-CommandOutput -Command 'git' -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Fallback ''

    return [ordered]@{
        gitSha               = Get-CommandOutput -Command 'git' -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD')
        gitBranch            = Get-CommandOutput -Command 'git' -Arguments @('-C', $RepoRoot, 'rev-parse', '--abbrev-ref', 'HEAD')
        gitDirty             = [bool]($gitStatus.Trim())
        k6Version            = Get-CommandOutput -Command 'k6' -Arguments @('version')
        dockerVersion        = Get-CommandOutput -Command 'docker' -Arguments @('version', '--format', '{{.Server.Version}}')
        dockerComposeVersion = Get-CommandOutput -Command 'docker' -Arguments @('compose', 'version', '--short')
        os                   = $osName
        cpu                  = [ordered]@{ model = $cpuModel; logicalCores = $logicalCores }
        memoryGb             = $memoryGb
        composeProject       = $BenchmarkProject
        backendBaseUrl       = $BaseUrl
        startedAt            = $StartedAt
        finishedAt           = (Get-Date).ToUniversalTime().ToString('o')
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

Assert-BenchmarkIsolation

if (-not $OutDir) {
    $OutDir = Join-Path $ScriptDir 'results'
}
$sessionName = "$Mode-$((Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'))"
$sessionDir = Join-Path $OutDir $sessionName
New-Item -ItemType Directory -Path $sessionDir -Force | Out-Null
$resultsFile = Join-Path $sessionDir 'results.json'

$startedAt = (Get-Date).ToUniversalTime().ToString('o')

# Plan the runs up front so summary.expectedRuns is decided before anything is
# measured: a run that later blows up still has to appear in the document.
$plan = @()
if ($Mode -eq 'full') {
    # Not $profile: that is an automatic PowerShell variable ($PROFILE).
    foreach ($contentionProfile in $ContentionProfiles) {
        for ($repeat = 1; $repeat -le $RepeatsPerProfile; $repeat++) {
            $plan += @{
                RunId = "contention-$($contentionProfile.Vus)x$($contentionProfile.Stock)-$repeat"
                Kind  = 'contention'
                Users = $contentionProfile.Vus
                Stock = $contentionProfile.Stock
            }
        }
    }
    $plan += @{ RunId = 'soak-1'; Kind = 'soak'; Users = $SoakUsers; Stock = $SoakUsers }
}
else {
    $plan += @{
        RunId = "contention-$($SmokeVus)x$($SmokeStock)-1"
        Kind  = 'contention'
        Users = $SmokeVus
        Stock = $SmokeStock
    }
}

Write-Host "benchmark mode=$Mode project=$BenchmarkProject runs=$($plan.Count) out=$sessionDir"

$runs = @()
try {
    Write-Host "starting the isolated stack (this builds the backend image on first run)..."
    Invoke-Compose -Arguments @('up', '-d', '--build', '--wait')
    Assert-ComposeIdentity
    Wait-BackendReady

    foreach ($planned in $plan) {
        $runs += Invoke-BenchmarkRun -RunId $planned.RunId -Kind $planned.Kind -Users $planned.Users -Stock $planned.Stock -SessionDir $sessionDir
    }
}
finally {
    $failedRuns = @($runs | Where-Object { $_.status -eq 'failed' }).Count
    $document = [ordered]@{
        schemaVersion = 1
        mode          = $Mode
        environment   = (Get-EnvironmentMetadata -StartedAt $startedAt)
        summary       = [ordered]@{
            expectedRuns = $plan.Count
            failedRuns   = $failedRuns
        }
        runs          = $runs
    }
    # UTF-8 *without* a BOM: PowerShell 5.1's `Set-Content -Encoding utf8` emits one,
    # and JSON.parse in verify-results.mjs rejects a leading U+FEFF.
    $json = $document | ConvertTo-Json -Depth 24
    [System.IO.File]::WriteAllText($resultsFile, $json, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ""
    Write-Host "results written to $resultsFile ($($runs.Count)/$($plan.Count) run(s) recorded, $failedRuns failed)"

    if (-not $KeepStack) {
        Write-Host "tearing down the isolated project (only '$BenchmarkProject' is touched)..."
        Invoke-Compose -Arguments @('down', '-v', '--remove-orphans') -IgnoreExitCode
    }
    else {
        Write-Host "-KeepStack: leaving '$BenchmarkProject' running; tear it down with"
        Write-Host "  docker compose -p $BenchmarkProject --project-directory . -f load-tests/benchmark/compose.benchmark.yaml down -v"
    }
}

if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Host ""
    Write-Host "verifying $resultsFile ..."
    & node $VerifierScript $resultsFile
    $verifyExit = $LASTEXITCODE
    if ($verifyExit -ne 0) {
        Write-Warning "verify-results.mjs reported violations (exit $verifyExit); the result document is preserved at $resultsFile"
    }
    exit $verifyExit
}

Write-Host "node not found on PATH; verify manually with: node $VerifierScript $resultsFile"
exit 0
