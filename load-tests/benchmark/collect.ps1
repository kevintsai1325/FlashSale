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
# $BaseUrl 給 PowerShell 這一側用：健康檢查、建立帳號、actuator 探針。這些都是低頻的循序
# 請求，不會踩到 host port 發布層的連線佇列上限。
$BaseUrl = "http://127.0.0.1:$BackendPort"

# $InternalBaseUrl 給 k6 用：k6 跑在 compose 網路內的容器裡，以服務名直接連 backend。
# 施壓流量絕對不能經過 Windows 的 host port，詳見 Invoke-K6 的註解。
$InternalBaseUrl = 'http://backend:8080'
$BenchmarkNetwork = "${BenchmarkProject}_default"
# 與開發者本機的 k6 版本對齊，讓「把 k6 搬進容器」這個改動只影響網路路徑，不影響 k6 行為。
$K6Image = 'grafana/k6:2.2.0'

# The contention matrix. Each profile is repeated $RepeatsPerProfile times so Task 5
# can report spread rather than a single lucky run.
$ContentionProfiles = @(
    @{ Vus = 30;  Stock = 10  },
    @{ Vus = 100; Stock = 30  },
    @{ Vus = 300; Stock = 100 }
)
$RepeatsPerProfile = 5

# Runs before the measured matrix, discarded from the result document. Exercises the
# same hot path (Lua reservation, Postgres tx, JWT filter chain) so the JVM has JITted
# it and HikariCP's pool is established before runs[0] is measured — see the ~4x
# slower first run in docs/portfolio/performance-report.md ("暖機離群值").
$WarmupProfile = @{ Vus = 20; Stock = 5 }
$WarmupRepeats = 2

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
    # k6 不再需要裝在 host 上：它以容器形式跑在 compose 網路內（見 Invoke-K6）。
    # 這裡只確認映像取得得到，讓缺映像在第一次施壓前就失敗，而不是跑到一半才爆。
    #
    # 探測期間必須把 $ErrorActionPreference 降為 Continue：Windows PowerShell 5.1 會把原生
    # 命令寫到 stderr 的每一行包成 ErrorRecord，在 Stop 之下「映像不存在」會直接拋例外，
    # 讓底下的 docker pull 永遠不會執行 —— 也就是說這個檢查會把它本來要處理的情況變成硬錯誤。
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & docker image inspect $K6Image 2>&1 | Out-Null
        $imagePresent = ($LASTEXITCODE -eq 0)
        if (-not $imagePresent) {
            Write-Host "pulling $K6Image ..."
            & docker pull $K6Image 2>&1 | Out-Host
            $imagePresent = ($LASTEXITCODE -eq 0)
        }
    }
    finally { $ErrorActionPreference = $previousPreference }
    if (-not $imagePresent) {
        throw "refusing to run: cannot obtain the k6 image $K6Image (see load-tests/benchmark/README.md, Prerequisites)"
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

# A fixed, known-password ADMIN account for a human to log into the frontend with
# (see load-tests/benchmark/README.md, "Browsing a kept stack") — distinct from
# New-AdminToken's per-run throwaway accounts, which nobody ever needs to log into by
# hand. Idempotent: reruns against a kept stack just re-promote the same account.
function Ensure-DefaultAdmin {
    $email = 'admin@test.com'
    $password = 'admin1234'
    $body = (@{ email = $email; password = $password } | ConvertTo-Json -Compress)
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/auth/register" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 30 | Out-Null
    }
    catch {
        # already registered from a previous run against a kept stack
    }
    Invoke-Psql -Sql "UPDATE users SET role = 'ADMIN' WHERE email = '$email';" | Out-Null
    Write-Host "default admin ready: $email / $password"
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
    param([string]$Script, [string[]]$EnvArgs, [string]$SessionDir)

    # k6 跑在 compose 網路內的容器裡，直接打 backend:8080，不經過發布到 Windows 的 host port。
    #
    # 為什麼：從 Windows 打 127.0.0.1:18080 時，300 條瞬間到達的新連線中約有 25-30% 會在 TCP
    # 握手階段就被拒絕（ECONNREFUSED）。被拒的是 Windows 的 port 發布層，Tomcat 從來沒看到
    # 那些連線，但 k6 會把它們記成請求失敗 —— 於是量測工具自己的限制被當成受測系統的行為。
    #
    # 實測（同一個 backend 容器、同一支腳本、相隔數秒）：
    #   VM 內部 → backend:8080        0 / 300 失敗
    #   Windows → 127.0.0.1:18080    75 / 300 失敗
    # 這也解釋了為什麼「調大 Tomcat accept-count」從來沒有可靠地解決這件事，以及為什麼
    # 「停掉同機其他工作負載」有效 —— 同機干擾正是透過這一層作用的。
    $scriptName = Split-Path -Leaf $Script
    $scriptMount = (Get-Item -LiteralPath $ScriptDir).FullName.Replace('\', '/')
    $sessionMount = (Get-Item -LiteralPath $SessionDir).FullName.Replace('\', '/')
    $arguments = @(
        'run', '--rm',
        '--network', $BenchmarkNetwork,
        '-v', "${scriptMount}:/scripts:ro",
        '-v', "${sessionMount}:/out",
        $K6Image,
        'run', "/scripts/$scriptName"
    ) + $EnvArgs
    # Out-Host, not the pipeline: otherwise k6's console output would be returned
    # alongside the exit code and the caller's `-ne 0` check would compare an array.
    & docker @arguments | Out-Host
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
    # k6 跑在容器裡，看到的是掛載點 /out，不是 Windows 的路徑。PowerShell 這一側仍然用
    # Windows 路徑讀回摘要，兩者指向同一個檔案。
    $tokensFileInK6 = "/out/" + (Split-Path -Leaf $tokensFile)
    $k6SummaryFileInK6 = "/out/" + (Split-Path -Leaf $k6SummaryFile)
    # Record only the file name: results.json is a publishable artifact, so it must
    # never carry the absolute path of the machine that produced it. The file always
    # lives next to results.json in the same session directory.
    $run.k6.summaryFile = Split-Path -Leaf $k6SummaryFile

    try {
        Reset-BenchmarkData -Stock $Stock
        $adminToken = New-AdminToken -RunId $RunId

        # --- prepare: accounts and tokens, outside the measured window ---
        $benchPassword = [guid]::NewGuid().ToString('N')
        $prepareExit = Invoke-K6 -Script $PrepareScript -SessionDir $SessionDir -EnvArgs @(
            '-e', "USERS=$Users",
            '-e', "RUN_ID=$RunId",
            '-e', "BASE_URL=$InternalBaseUrl",
            '-e', "BENCH_PASSWORD=$benchPassword",
            '-e', "TOKENS_OUT=$tokensFileInK6"
        )
        if ($prepareExit -ne 0) {
            throw "prepare.js exited with code $prepareExit"
        }

        # --- measure ---
        if ($Kind -eq 'contention') {
            $exitCode = Invoke-K6 -Script $PurchaseScript -SessionDir $SessionDir -EnvArgs @(
                '-e', "VUS=$Users",
                '-e', "STOCK=$Stock",
                '-e', "RUN_ID=$RunId",
                '-e', "BASE_URL=$InternalBaseUrl",
                '-e', "TOKENS_FILE=$tokensFileInK6",
                '-e', "SUMMARY_OUT=$k6SummaryFileInK6"
            )
        }
        else {
            $exitCode = Invoke-K6 -Script $SoakScript -SessionDir $SessionDir -EnvArgs @(
                '-e', "RUN_ID=$RunId",
                '-e', "STOCK=$Stock",
                '-e', "BASE_URL=$InternalBaseUrl",
                '-e', "TOKENS_FILE=$tokensFileInK6",
                '-e', "SUMMARY_OUT=$k6SummaryFileInK6"
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
        # 記錄實際施壓的那個 k6（容器內的），不是 host 上可能存在的另一個版本。
        k6Version            = Get-CommandOutput -Command 'docker' -Arguments @('run', '--rm', $K6Image, 'version')
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

    if ($Mode -eq 'full') {
        Write-Host ""
        Write-Host "=== warm-up: $WarmupRepeats x (vus=$($WarmupProfile.Vus), stock=$($WarmupProfile.Stock)), discarded ==="
        for ($repeat = 1; $repeat -le $WarmupRepeats; $repeat++) {
            Invoke-BenchmarkRun -RunId "warmup-$repeat" -Kind 'contention' -Users $WarmupProfile.Vus -Stock $WarmupProfile.Stock -SessionDir $sessionDir | Out-Null
        }
    }

    foreach ($planned in $plan) {
        $runs += Invoke-BenchmarkRun -RunId $planned.RunId -Kind $planned.Kind -Users $planned.Users -Stock $planned.Stock -SessionDir $sessionDir
    }

    if ($KeepStack) {
        # Every run's Reset-BenchmarkData truncates `users`, so this only makes sense
        # once no more runs are coming — otherwise the very next reset would wipe it.
        Ensure-DefaultAdmin
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
