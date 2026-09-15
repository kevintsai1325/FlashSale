# FlashSale 作品集文件契約測試 / portfolio documentation contract test.
#
# 執行方式 (Windows PowerShell 5.1):
#   powershell.exe -ExecutionPolicy Bypass -File scripts/tests/portfolio-docs-test.ps1
# (若本機的 execution policy 允許執行未簽章腳本,-ExecutionPolicy Bypass 可以省略。)
#
# 檢查對象:docs/portfolio/ 底下的四份深入文件,以及根目錄的 README.md。
#
# 相容性備註：本檔案刻意只使用 Windows PowerShell 5.1 支援的語法
# (不使用 ternary、null-coalescing、null-conditional 或 '&&' / '||' 串接)，
# 並以 UTF-8 with BOM 儲存，讓 PowerShell 5.1 正確解碼下方的繁體中文字串。

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$portfolioDir = Join-Path $repoRoot 'docs\portfolio'
$failures = New-Object 'System.Collections.Generic.List[string]'

function Add-Failure {
    param([string]$Message)
    $failures.Add($Message) | Out-Null
}

function Read-TextFile {
    param([string]$Path)
    $text = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if ($null -eq $text) {
        return ''
    }
    return $text
}

function ConvertTo-RouteTemplate {
    param([string]$Path)
    $value = [regex]::Replace($Path, '\{[^}]*\}', '{}')
    $value = $value -replace '/+', '/'
    if ($value.Length -gt 1 -and $value.EndsWith('/')) {
        $value = $value.Substring(0, $value.Length - 1)
    }
    return $value
}

function Get-ControllerRouteTemplates {
    $templates = New-Object 'System.Collections.Generic.HashSet[string]'
    # P4 之後路由散在兩個服務裡：搶購的兩個端點在 purchase-service，其餘在 backend。
    # 只掃 backend 的話，文件裡正確的搶購路徑會被誤判成「沒有對應的路由」。
    $javaRoots = @(
        (Join-Path $repoRoot 'backend\src\main\java'),
        (Join-Path $repoRoot 'purchase-service\src\main\java'),
        # P5：訂單與付款的路由在 order-service。
        (Join-Path $repoRoot 'order-service\src\main\java'),
        # P6：即時大屏的 SSE 端點在 analytics-service。
        (Join-Path $repoRoot 'analytics-service\src\main\java')
    )
    $controllers = @($javaRoots |
        Where-Object { Test-Path -LiteralPath $_ } |
        ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -Filter '*Controller.java' })
    foreach ($controller in $controllers) {
        $source = Read-TextFile -Path $controller.FullName
        $base = ''
        $classMapping = [regex]::Match($source, '@RequestMapping\(\s*(?:(?:value|path)\s*=\s*)?"([^"]*)"')
        if ($classMapping.Success) {
            $base = $classMapping.Groups[1].Value
            # class 層級的 @RequestMapping 本身也是文件中合理的「這組 API 的基底路徑」寫法。
            $templates.Add((ConvertTo-RouteTemplate -Path $base)) | Out-Null
        }
        # `path = "..."` 與 `value = "..."` 都要吃：analytics-service 的 SSE 端點用的是 path。
        $methodMappings = [regex]::Matches($source, '@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:(?:value|path)\s*=\s*)?(?:"([^"]*)")?')
        foreach ($methodMapping in $methodMappings) {
            $suffix = $methodMapping.Groups[1].Value
            $full = $base + $suffix
            if ([string]::IsNullOrWhiteSpace($full)) {
                continue
            }
            $templates.Add((ConvertTo-RouteTemplate -Path $full)) | Out-Null
        }
    }
    return $templates
}

function Get-NginxRouteTemplates {
    $templates = New-Object 'System.Collections.Generic.List[string]'
    $nginxConf = Read-TextFile -Path (Join-Path $repoRoot 'nginx\nginx.conf')
    if ($nginxConf -match 'location\s*=\s*/actuator/health') {
        $templates.Add('/actuator/health') | Out-Null
    }
    if ($nginxConf -match 'location\s*~\s*\^/actuator/health/\(liveness\|readiness\)\$') {
        $templates.Add('/actuator/health/liveness') | Out-Null
        $templates.Add('/actuator/health/readiness') | Out-Null
    }
    if ($nginxConf -match 'location\s*=\s*/actuator/metrics') {
        $templates.Add('/actuator/metrics') | Out-Null
    }
    if ($nginxConf -match 'location\s*~\s*\^/actuator/metrics/') {
        $templates.Add('/actuator/metrics/{}') | Out-Null
    }
    if ($nginxConf -match 'location\s+/swagger-ui/') {
        $templates.Add('/swagger-ui') | Out-Null
        $templates.Add('/swagger-ui/{}') | Out-Null
    }
    if ($nginxConf -match 'location\s+/v3/api-docs') {
        $templates.Add('/v3/api-docs') | Out-Null
    }
    return $templates
}

function Test-SegmentIsPlaceholder {
    param([string]$Segment)
    if ($Segment.StartsWith('{')) {
        return $true
    }
    if ($Segment.StartsWith('$')) {
        return $true
    }
    if ($Segment.StartsWith('<')) {
        return $true
    }
    return $false
}

function Test-PathMatchesTemplate {
    param([string]$Path, [string]$Template)
    $pathSegments = $Path.Trim('/').Split('/')
    $templateSegments = $Template.Trim('/').Split('/')
    if ($pathSegments.Count -ne $templateSegments.Count) {
        return $false
    }
    for ($i = 0; $i -lt $pathSegments.Count; $i++) {
        $pathSegment = $pathSegments[$i]
        $templateSegment = $templateSegments[$i]
        if (Test-SegmentIsPlaceholder -Segment $templateSegment) {
            continue
        }
        if (Test-SegmentIsPlaceholder -Segment $pathSegment) {
            continue
        }
        if ($pathSegment -ne $templateSegment) {
            return $false
        }
    }
    return $true
}

$documents = [ordered]@{
    'architecture.md' = @(
        '# FlashSale 架構深入說明',
        '## 系統全貌',
        '## 服務責任邊界',
        '## 核心搶購資料流',
        '## 一致性與補償機制',
        '## 可觀測性與稽核',
        '## 資料模型重點'
    )
    'api-examples.md'  = @(
        '# FlashSale API 操作範例',
        '## 前置準備',
        '## 註冊與登入',
        '## 瀏覽搶購活動',
        '## 送出搶購請求',
        '## 查詢搶購請求狀態',
        '## 我的訂單',
        '## 付款與取消',
        '## 管理者 API',
        '## 錯誤格式'
    )
    'trade-offs.md'    = @(
        '# FlashSale 工程取捨',
        '## Redis Lua 預扣庫存',
        '## Transactional Outbox',
        '## 補償機制而非分散式交易',
        '## 付款逾時掃描',
        '## 關閉 OSIV',
        '## Actuator 暴露範圍',
        '## 本機自簽 TLS',
        '## 只交付 Docker Compose'
    )
    'demo-script.md'   = @(
        '# FlashSale Demo 腳本',
        '## 展示前置檢查',
        '## 分段腳本',
        '## 備援方案',
        '## 收尾'
    )
    'README.md'        = @(
        '# FlashSale',
        '## 現況與證據',
        '## 畫面',
        '## 系統全貌',
        '## 核心搶購資料流',
        '## 快速開始',
        '## Demo 資料',
        '## 深入文件',
        '## 詳細設定',
        '## 已知限制',
        '## CI'
    )
}

function Get-DocumentPath {
    param([string]$Name)
    if ($Name -eq 'README.md') {
        return (Join-Path $repoRoot 'README.md')
    }
    return (Join-Path $portfolioDir $Name)
}

$forbiddenSecretPatterns = [ordered]@{
    'BEGIN [A-Z ]*PRIVATE KEY'          = 'PEM 私鑰內容'
    'BEGIN PUBLIC KEY'                  = 'PEM 公鑰內容'
    'eyJ[A-Za-z0-9_-]{10,}'             = '疑似真實 JWT'
    'refresh_token=[A-Za-z0-9]'         = '疑似真實 refresh token Cookie 值'
    'DEMO_(USER|ADMIN)_PASSWORD=[''"]?[A-Za-z0-9]' = '寫死的 demo 密碼'
    'GMAIL_APP_PASSWORD=\S'             = '寫死的 Gmail 應用程式密碼'
    'JWT_(PRIVATE|PUBLIC)_KEY=\S'       = '寫死的 JWT 金鑰值'
    '[A-Za-z]:\\'                       = '本機 Windows 絕對路徑'
    '(^|\s)/(home|Users|mnt)/'          = '本機 POSIX 絕對路徑'
}

$forbiddenStaleClaims = @(
    '分成兩段',
    '分開的兩條',
    '沒有持久化 trace context',
    '沒有透過 Nginx 對外反代',
    'open-in-view: true',
    '同步版本'
)

# README 專屬的舊敘述：Week 7 進度、舊測試數字、已被修正的 nginx / outbox trace 說明。
$forbiddenReadmeClaims = @(
    '未開始',
    '131 個',
    '沒有 `/actuator/` 的 location 規則',
    '不是同一條',
    'out of scope for Week 1',
    'will be expanded in a later week'
)

# 只出現在簡體中文的字形；作品集文件與 README 一律使用繁體中文。
$simplifiedOnlyCharacters = @(
    '说', '设', '务', '构', '统', '资', '产', '请', '应', '买', '单', '数', '据',
    '测', '试', '证', '现', '时', '间', '网', '页', '库', '户', '认', '术', '语',
    '处', '权', '载', '转', '递', '连', '释', '义', '车', '边', '见', '觉', '讲',
    '读', '写', '错', '误', '态', '级', '标', '题', '类', '样', '总', '结', '态'
)

$allowedEmailDomains = @('example.test', 'example.com')
$verifiableHosts = @('localhost:8443', '127.0.0.1:8443', 'localhost:8080', 'backend:8080')

Write-Host 'FlashSale portfolio documentation contract test'
Write-Host ('repo root: {0}' -f $repoRoot)

$controllerTemplates = Get-ControllerRouteTemplates
$nginxTemplates = Get-NginxRouteTemplates
$allTemplates = New-Object 'System.Collections.Generic.List[string]'
foreach ($template in $controllerTemplates) { $allTemplates.Add($template) | Out-Null }
foreach ($template in $nginxTemplates) { $allTemplates.Add($template) | Out-Null }

if ($allTemplates.Count -lt 10) {
    Add-Failure ('route extraction produced too few templates ({0}); controller/nginx parsing is broken' -f $allTemplates.Count)
}

# P5：指標跟著服務拆開了 —— 預扣相關的在 purchase-service，建單的在 order-service。
# 兩份都要讀，否則文件裡正確的指標名稱會被誤判成不存在。
$purchaseMetricsSource = Read-TextFile -Path (Join-Path $repoRoot 'purchase-service\src\main\java\com\flashsale\purchase\metrics\PurchaseMetrics.java')
$orderMetricsSource = Read-TextFile -Path (Join-Path $repoRoot 'order-service\src\main\java\com\flashsale\common\metrics\OrderMetrics.java')
$metricsSource = $purchaseMetricsSource + $orderMetricsSource

foreach ($documentName in $documents.Keys) {
    $documentPath = Get-DocumentPath -Name $documentName
    if (-not (Test-Path -LiteralPath $documentPath)) {
        Add-Failure ('missing document: {0}' -f $documentName)
        continue
    }

    $text = Read-TextFile -Path $documentPath
    $lines = $text -split "`r?`n"

    # 1. 必要標題
    foreach ($heading in $documents[$documentName]) {
        $found = $false
        foreach ($line in $lines) {
            if ($line.Trim() -eq $heading) {
                $found = $true
                break
            }
        }
        if (-not $found) {
            Add-Failure ('{0}: missing required heading "{1}"' -f $documentName, $heading)
        }
    }

    # 2. code fence / Mermaid fence 平衡
    $openFence = $false
    $mermaidBlocks = 0
    foreach ($line in $lines) {
        if ($line.TrimEnd() -match '^\s*```') {
            if ($openFence) {
                $openFence = $false
            } else {
                $openFence = $true
                if ($line.Trim() -match '^```mermaid$') {
                    $mermaidBlocks++
                }
            }
        }
    }
    if ($openFence) {
        Add-Failure ('{0}: unbalanced code fence (a ``` block is never closed)' -f $documentName)
    }
    if (($documentName -eq 'architecture.md' -or $documentName -eq 'README.md') -and $mermaidBlocks -lt 2) {
        Add-Failure ('{0}: expected at least 2 mermaid diagrams, found {1}' -f $documentName, $mermaidBlocks)
    }

    # 3. 相對連結必須可解析
    $linkMatches = [regex]::Matches($text, '\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)')
    foreach ($linkMatch in $linkMatches) {
        $target = $linkMatch.Groups[1].Value
        if ($target -match '^(https?:|mailto:|#)') {
            continue
        }
        $target = ($target -split '#')[0]
        if ([string]::IsNullOrWhiteSpace($target)) {
            continue
        }
        $resolved = Join-Path (Split-Path -Parent $documentPath) $target
        if (-not (Test-Path -LiteralPath $resolved)) {
            Add-Failure ('{0}: relative link does not resolve: {1}' -f $documentName, $target)
        }
    }

    # 4. 禁止的機密樣式
    foreach ($pattern in $forbiddenSecretPatterns.Keys) {
        $secretMatch = [regex]::Match($text, $pattern)
        if ($secretMatch.Success) {
            Add-Failure ('{0}: forbidden content ({1}) matched: {2}' -f $documentName, $forbiddenSecretPatterns[$pattern], $secretMatch.Value)
        }
    }

    # 5. 只允許 demo / 範例網域的 email
    $emailMatches = [regex]::Matches($text, '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}')
    foreach ($emailMatch in $emailMatches) {
        $domain = ($emailMatch.Value -split '@')[1]
        if ($allowedEmailDomains -notcontains $domain) {
            Add-Failure ('{0}: non-demo email address: {1}' -f $documentName, $emailMatch.Value)
        }
    }

    # 6. 已失效的舊敘述
    foreach ($claim in $forbiddenStaleClaims) {
        if ($text.Contains($claim)) {
            Add-Failure ('{0}: stale claim present: "{1}"' -f $documentName, $claim)
        }
    }
    if ($documentName -eq 'README.md') {
        foreach ($claim in $forbiddenReadmeClaims) {
            if ($text.Contains($claim)) {
                Add-Failure ('{0}: stale claim present: "{1}"' -f $documentName, $claim)
            }
        }
    }

    # 6b. 敘述必須是繁體中文（技術名詞保留英文）
    $cjkCount = [regex]::Matches($text, '[一-鿿]').Count
    if ($cjkCount -lt 600) {
        Add-Failure ('{0}: prose does not look like Traditional Chinese (only {1} CJK characters)' -f $documentName, $cjkCount)
    }
    foreach ($simplified in $simplifiedOnlyCharacters) {
        if ($text.Contains($simplified)) {
            Add-Failure ('{0}: simplified Chinese character present: "{1}"' -f $documentName, $simplified)
        }
    }

    # 7. API 路徑必須存在於 controller 或 nginx 設定
    # 結尾的 lookahead 讓 './api-examples.md' 這種相對連結不會被誤判成 API 路徑 '/api'。
    $pathMatches = [regex]::Matches($text, '(?:(?<scheme>https?://)(?<host>[^/\s"''`]+))?(?<path>/(?:api|actuator|internal|swagger-ui|v3)(?:/[A-Za-z0-9_.${}<>*:-]+)*/?)(?![A-Za-z0-9_-])')
    foreach ($pathMatch in $pathMatches) {
        $urlHost = $pathMatch.Groups['host'].Value
        if (-not [string]::IsNullOrWhiteSpace($urlHost)) {
            if ($verifiableHosts -notcontains $urlHost) {
                continue
            }
        }
        $rawPath = $pathMatch.Groups['path'].Value
        $candidate = ConvertTo-RouteTemplate -Path $rawPath
        # 以 '/' 結尾的寫法代表「路徑前綴」(對應 nginx 的 location 前綴),
        # 只要求有任何一條實際路由落在該前綴下,而不要求它本身是端點。
        if ($rawPath.Length -gt 1 -and $rawPath.EndsWith('/')) {
            $prefix = $candidate + '/'
            $prefixMatched = $false
            foreach ($template in $allTemplates) {
                if ($template.StartsWith($prefix)) {
                    $prefixMatched = $true
                    break
                }
            }
            if (-not $prefixMatched) {
                Add-Failure ('{0}: documented path prefix has no matching route: {1}' -f $documentName, $rawPath)
            }
            continue
        }
        if ($candidate.EndsWith('/**')) {
            $prefix = $candidate.Substring(0, $candidate.Length - 3)
            $prefixMatched = $false
            foreach ($template in $allTemplates) {
                if ($template.StartsWith($prefix)) {
                    $prefixMatched = $true
                    break
                }
            }
            if (-not $prefixMatched) {
                Add-Failure ('{0}: documented path prefix has no matching route: {1}' -f $documentName, $candidate)
            }
            continue
        }
        if ($candidate.Contains('*')) {
            continue
        }
        $matched = $false
        foreach ($template in $allTemplates) {
            if (Test-PathMatchesTemplate -Path $candidate -Template $template) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            Add-Failure ('{0}: documented API path matches no controller route or nginx location: {1}' -f $documentName, $candidate)
        }
    }

    # 8. 自訂 metric 名稱必須真的存在於程式碼
    # `purchase.resolved` 是 RabbitMQ 的 routing key，不是指標。
    # 此正則只看得到字形，分不出語意，所以把路由鍵明列排除。
    $routingKeys = @('purchase.resolved')
    $metricMatches = [regex]::Matches($text, 'purchase\.[a-z]+(?:\.[a-z]+)*')
    foreach ($metricMatch in $metricMatches) {
        if ($routingKeys -contains $metricMatch.Value) {
            continue
        }
        if (-not $metricsSource.Contains($metricMatch.Value)) {
            Add-Failure ('{0}: metric name not found in any metrics class: {1}' -f $documentName, $metricMatch.Value)
        }
    }
}

# 9. api-examples.md 必須使用 shell 變數而非真實 token，且示範 Idempotency-Key
$apiExamplesPath = Join-Path $portfolioDir 'api-examples.md'
if (Test-Path -LiteralPath $apiExamplesPath) {
    $apiExamplesText = Read-TextFile -Path $apiExamplesPath
    if (-not $apiExamplesText.Contains('$ACCESS_TOKEN')) {
        Add-Failure 'api-examples.md: expected curl examples to use the $ACCESS_TOKEN shell variable'
    }
    if (-not $apiExamplesText.Contains('Idempotency-Key')) {
        Add-Failure 'api-examples.md: expected the purchase example to send the Idempotency-Key header'
    }
    if (-not $apiExamplesText.Contains('demo.user@example.test')) {
        Add-Failure 'api-examples.md: expected the documented demo user identifier demo.user@example.test'
    }
}

function Get-FirstMermaidBlock {
    param([string]$Text)
    $block = New-Object 'System.Text.StringBuilder'
    $inMermaid = $false
    foreach ($line in ($Text -split "`r?`n")) {
        if (-not $inMermaid) {
            if ($line.Trim() -eq '```mermaid') {
                $inMermaid = $true
            }
            continue
        }
        if ($line.TrimEnd() -match '^\s*```') {
            break
        }
        $block.AppendLine($line) | Out-Null
    }
    return $block.ToString()
}

# 9.5 架構總圖必須列出每一個部署中的 workload
#
# 這條規則的由來：P5 與 P6 各加了服務，但「系統全貌」的總圖停在 P4-1，
# 連過兩個階段都沒被抓到——因為當時沒有任何斷言在看那張圖。
# 路由有斷言（第 4 節）所以一直準，圖沒有所以爛掉。**守門只守它斷言的東西。**
#
# README 與 architecture.md 兩份都要檢查：第一次補這條斷言時只蓋了 architecture.md，
# 結果 README 的同一張圖仍然是過期的——一份沒被斷言蓋到的副本，就是下一次的漂移點。
#
# 方向是單向的：k8s 有的，圖上必須提到。反過來（圖上畫了但叢集沒有）不檢查，
# 因為從 mermaid 可靠地反解出節點名稱要寫一個小 parser，而實際發生過的漂移是前者。
$k8sBaseDir = Join-Path $repoRoot 'k8s\base'
$overviewDocuments = [ordered]@{
    'architecture.md' = (Join-Path $portfolioDir 'architecture.md')
    'README.md'       = (Join-Path $repoRoot 'README.md')
}
if (Test-Path -LiteralPath $k8sBaseDir) {
    $workloads = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($manifest in @(Get-ChildItem -LiteralPath $k8sBaseDir -Filter '*.yaml')) {
        $manifestText = Read-TextFile -Path $manifest.FullName
        foreach ($chunk in [regex]::Split($manifestText, '(?m)^---\s*$')) {
            if ($chunk -notmatch '(?m)^kind:\s*(?:Deployment|StatefulSet)\s*$') {
                continue
            }
            # 這些 manifest 的 metadata 是 flow-style（`metadata: {name: x, ...}`），
            # 所以先試 flow-style，再退回一般的區塊式縮排。
            $nameMatch = [regex]::Match($chunk, '(?m)^metadata:\s*\{[^}]*?name:\s*([A-Za-z0-9.-]+)')
            if (-not $nameMatch.Success) {
                $nameMatch = [regex]::Match($chunk, '(?m)^  name:\s*([A-Za-z0-9.-]+)\s*$')
            }
            if ($nameMatch.Success) {
                $workloads.Add($nameMatch.Groups[1].Value) | Out-Null
            }
        }
    }
    if ($workloads.Count -lt 1) {
        Add-Failure 'no Deployment/StatefulSet found under k8s/base - the diagram guard would pass vacuously'
    }

    foreach ($overviewName in $overviewDocuments.Keys) {
        $overviewPath = $overviewDocuments[$overviewName]
        if (-not (Test-Path -LiteralPath $overviewPath)) {
            continue
        }
        # 只看第一個 mermaid 區塊：那是「系統全貌」的總圖，其餘是各節的局部圖。
        $overviewText = Get-FirstMermaidBlock -Text (Read-TextFile -Path $overviewPath)
        if ([string]::IsNullOrWhiteSpace($overviewText)) {
            Add-Failure ('{0}: could not find the system overview mermaid diagram' -f $overviewName)
            continue
        }
        $overviewLowered = $overviewText.ToLowerInvariant()
        foreach ($workload in $workloads) {
            if (-not $overviewLowered.Contains($workload.ToLowerInvariant())) {
                Add-Failure ('{0}: the system overview diagram never mentions the deployed workload {1}' -f $overviewName, $workload)
            }
        }
    }
}

# 10. README 專屬契約：作品集的公開入口
$readmePath = Join-Path $repoRoot 'README.md'
if (-not (Test-Path -LiteralPath $readmePath)) {
    Add-Failure 'missing README.md'
} else {
    $readmeText = Read-TextFile -Path $readmePath
    $readmeLines = $readmeText -split "`r?`n"

    # 10.1 CI badge 必須是連到 workflow 的即時狀態，而不是截圖
    if (-not $readmeText.Contains('![CI](https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml/badge.svg)')) {
        Add-Failure 'README.md: missing the live CI status badge image'
    }
    if (-not $readmeText.Contains('(https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml)')) {
        Add-Failure 'README.md: CI badge is not linked to the workflow page'
    }

    # 10.2 證據先行：證據段落要在第一屏，且排在安裝/啟動說明之前
    $evidenceLine = -1
    $quickStartLine = -1
    for ($i = 0; $i -lt $readmeLines.Count; $i++) {
        $trimmedLine = $readmeLines[$i].Trim()
        if ($evidenceLine -lt 0 -and $trimmedLine -eq '## 現況與證據') { $evidenceLine = $i }
        if ($quickStartLine -lt 0 -and $trimmedLine -eq '## 快速開始') { $quickStartLine = $i }
    }
    if ($evidenceLine -lt 0) {
        Add-Failure 'README.md: missing the evidence-first section "## 現況與證據"'
    } else {
        if ($evidenceLine -gt 20) {
            Add-Failure ('README.md: evidence section starts at line {0}; it must stay on the first screen (line 20 or earlier)' -f ($evidenceLine + 1))
        }
        if ($quickStartLine -ge 0 -and $quickStartLine -lt $evidenceLine) {
            Add-Failure 'README.md: setup instructions appear before the evidence section'
        }
    }

    # 10.3 真實測試數字（後端 177 / 前端 70），並且標示為「最後已知」而非本次重跑
    if (-not $readmeText.Contains('177 個測試')) {
        Add-Failure 'README.md: missing the real backend test count (177 個測試)'
    }
    if (-not $readmeText.Contains('70 個測試')) {
        Add-Failure 'README.md: missing the real frontend test count (70 個測試)'
    }
    if (-not $readmeText.Contains('最後已知')) {
        Add-Failure 'README.md: test counts must be qualified as last-known-verified ("最後已知")'
    }

    # 10.4 真實服務數與健康度項目數
    #
    # 服務數從 compose.yaml 數出來，不寫死在這裡。原本寫死成 8，
    # 於是 P4/P5/P6 把 stack 從 8 個長到 17 個時，這條斷言反而變成
    # 「守著一個已經錯掉的數字」——它會擋住正確的更新，而不是擋住漂移。
    $composeText = Read-TextFile -Path (Join-Path $repoRoot 'compose.yaml')
    $composeBody = [regex]::Replace($composeText, '(?s)^.*?(?m:^services:\s*$)', '')
    $composeServiceCount = [regex]::Matches($composeBody, '(?m)^  [a-z][A-Za-z0-9-]*:\s*$').Count
    if ($composeServiceCount -lt 1) {
        Add-Failure 'compose.yaml: could not count services - the README service-count guard would pass vacuously'
    } elseif (-not $readmeText.Contains(('{0} 個服務' -f $composeServiceCount))) {
        Add-Failure ('README.md: missing the real Compose service count ({0} 個服務)' -f $composeServiceCount)
    }
    if (-not $readmeText.Contains('8 項健康度')) {
        Add-Failure 'README.md: missing the real system-health item count (8 項健康度)'
    }

    # 10.5 兩張 Mermaid 圖：系統全貌 flowchart 與搶購 sequenceDiagram
    if (-not $readmeText.Contains('flowchart')) {
        Add-Failure 'README.md: missing the system-overview mermaid flowchart'
    }
    if (-not $readmeText.Contains('sequenceDiagram')) {
        Add-Failure 'README.md: missing the purchase-flow mermaid sequenceDiagram'
    }

    # 10.6 可直接複製的啟動指令
    if (-not $readmeText.Contains('docker compose up --build -d')) {
        Add-Failure 'README.md: missing the copy-paste startup command'
    }

    # 10.7 Demo 資料指令
    if (-not $readmeText.Contains('scripts/demo-data.sh seed')) {
        Add-Failure 'README.md: missing the demo seed command (scripts/demo-data.sh seed)'
    }
    if (-not $readmeText.Contains('scripts/demo-data.sh cleanup')) {
        Add-Failure 'README.md: missing the demo cleanup command (scripts/demo-data.sh cleanup)'
    }

    # 10.8 六張截圖必須以圖片語法嵌入，且檔案真的存在
    $screenshots = @('storefront', 'purchase-result', 'my-orders', 'admin-dashboard', 'system-health', 'zipkin-trace')
    foreach ($screenshot in $screenshots) {
        $relative = 'docs/portfolio/assets/{0}.png' -f $screenshot
        if ($readmeText -notmatch ('!\[[^\]]*\]\(' + [regex]::Escape($relative) + '\)')) {
            Add-Failure ('README.md: missing embedded screenshot: {0}' -f $relative)
        }
        $screenshotPath = Join-Path $repoRoot ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $screenshotPath)) {
            Add-Failure ('README.md: referenced screenshot file does not exist: {0}' -f $relative)
        }
    }

    # 10.9 壓測摘要數字必須來自結果文件，不能是自己編出來的
    $benchmarkPath = Join-Path $portfolioDir 'data\benchmark-results.json'
    if (-not (Test-Path -LiteralPath $benchmarkPath)) {
        Add-Failure 'missing docs/portfolio/data/benchmark-results.json'
    } else {
        $benchmarkText = Read-TextFile -Path $benchmarkPath

        # 結果文件跟著 repo 一起公開，所以不能帶上產生它的那台機器的本機絕對路徑
        # （檔名本身沒問題，k6 summary 一律跟 results.json 放在同一個 session 目錄）。
        foreach ($pathPattern in @('[A-Za-z]:\\', '/(home|Users|mnt)/')) {
            $pathMatch = [regex]::Match($benchmarkText, $pathPattern)
            if ($pathMatch.Success) {
                Add-Failure ('benchmark-results.json: 可公開的結果文件內含本機絕對路徑: {0}' -f $pathMatch.Value)
            }
        }

        $benchmark = $benchmarkText | ConvertFrom-Json
        $runs = @($benchmark.runs)
        $soakRun = $null
        foreach ($run in $runs) {
            if ($run.kind -eq 'soak') {
                $soakRun = $run
                break
            }
        }
        if ($null -eq $soakRun) {
            Add-Failure 'benchmark-results.json: no soak run found'
        }

        function Get-MedianValue {
            param([double[]]$Values)
            $sorted = @($Values | Sort-Object)
            return $sorted[[int](($sorted.Count - 1) / 2)]
        }

        function Test-ReadmeNumber {
            param([string]$Label, [double]$Value)
            $oneDecimal = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, '{0:0.0}', $Value)
            $trimmed = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, '{0:0.###}', $Value)
            if ((-not $readmeText.Contains($oneDecimal)) -and (-not $readmeText.Contains($trimmed))) {
                Add-Failure ('README.md: benchmark number missing or not sourced from benchmark-results.json: {0} (expected {1})' -f $Label, $oneDecimal)
            }
        }

        $tier30 = @()
        $tier100 = @()
        foreach ($run in $runs) {
            if ($run.kind -ne 'contention') { continue }
            if ($run.vus -eq 30) { $tier30 += [double]$run.metrics.acceptedLatencyMs.p95 }
            if ($run.vus -eq 100) { $tier100 += [double]$run.metrics.acceptedLatencyMs.p95 }
        }
        if ($tier30.Count -lt 5 -or $tier100.Count -lt 5) {
            Add-Failure 'benchmark-results.json: expected 5 runs each for the 30-VU and 100-VU contention tiers'
        } else {
            Test-ReadmeNumber -Label '30 VU accepted p95 median' -Value (Get-MedianValue -Values $tier30)
            Test-ReadmeNumber -Label '100 VU accepted p95 median' -Value (Get-MedianValue -Values $tier100)
        }

        if ($null -ne $soakRun) {
            Test-ReadmeNumber -Label 'soak accepted median' -Value ([double]$soakRun.metrics.acceptedLatencyMs.med)
            Test-ReadmeNumber -Label 'soak completed median' -Value ([double]$soakRun.metrics.completedLatencyMs.med)
            $soakOrders = [int]$soakRun.invariants.ordersCreated
            $groupedOrders = $soakOrders.ToString('N0', [System.Globalization.CultureInfo]::InvariantCulture)
            if ((-not $readmeText.Contains($groupedOrders)) -and (-not $readmeText.Contains($soakOrders.ToString()))) {
                Add-Failure ('README.md: soak order count not sourced from benchmark-results.json (expected {0})' -f $groupedOrders)
            }
        }

        $expectedRuns = [int]$benchmark.summary.expectedRuns
        if (-not $readmeText.Contains(('{0} 次' -f $expectedRuns))) {
            Add-Failure ('README.md: missing the real benchmark run count ({0} 次)' -f $expectedRuns)
        }

        # 引用 300 VU 的數字時，若資料真的顯示連線遺失，必須把資料品質警語一起帶上。
        #
        # 這裡原本寫死「必須出現 212」。那個數字來自 ee01e0e 那一版的資料：每次約 88 筆請求
        # 在建立 TCP 連線階段就被拒絕，300 個買家實際只有約 212 個有效。後來 32c65a9 重新
        # 收集，15 次競爭執行全部 accepted == vus、零遺失，README 的但書也就正確地拿掉了 ——
        # 但這條規則被留了下來，於是它開始要求 README 標註一個已經不存在的問題。
        #
        # 改為從 benchmark 資料推導：有遺失才要求但書，遺失多少就要求標註多少。
        # 這樣未來若某次收集又出現連線被拒，規則會自動重新發作，而且會指出正確的數字。
        $lossyRuns = @($benchmark.runs | Where-Object {
            $_.kind -eq 'contention' -and $null -ne $_.vus -and $null -ne $_.outcomes.accepted -and
            [int]$_.outcomes.accepted -lt [int]$_.vus
        })
        if (($lossyRuns.Count -gt 0) -and ($readmeText.Contains('300 個買家') -or $readmeText.Contains('474.1'))) {
            $worst = @($lossyRuns | Sort-Object { [int]$_.outcomes.accepted })[0]
            $effective = [int]$worst.outcomes.accepted
            if (-not $readmeText.Contains([string]$effective)) {
                Add-Failure ('README.md: benchmark data shows connection loss (as few as {0} of {1} buyers landed in {2}) but README carries no caveat naming that number' -f $effective, [int]$worst.vus, $worst.runId)
            }
        }
    }

    # 10.10 必須連到每一份作品集文件
    $portfolioDocuments = @(
        'docs/portfolio/architecture.md',
        'docs/portfolio/api-examples.md',
        'docs/portfolio/trade-offs.md',
        'docs/portfolio/demo-script.md',
        'docs/portfolio/performance-report.md'
    )
    foreach ($portfolioDocument in $portfolioDocuments) {
        if ($readmeText -notmatch ('\]\(' + [regex]::Escape($portfolioDocument) + '(?:#[^)]*)?\)')) {
            Add-Failure ('README.md: missing link to {0}' -f $portfolioDocument)
        }
    }

    # 10.11 誠實揭露限制，且不得宣稱 production 容量
    if (-not $readmeText.Contains('自簽')) {
        Add-Failure 'README.md: limitations must mention the local self-signed TLS certificate'
    }
    if (-not $readmeText.Contains('SLA')) {
        Add-Failure 'README.md: limitations must state that the numbers are not a production capacity/SLA claim'
    }
    # Week 8 P3 之後飽和測試已經做了，這裡改成守住真正還成立的那個邊界：
    # 「現況與證據」那一節的 soak/競爭負載數字本身沒有加壓到飽和，不能當吞吐上限。
    if (-not $readmeText.Contains('沒有加壓到飽和')) {
        Add-Failure 'README.md: limitations must state that the headline load numbers were not driven to saturation'
    }
    if (-not $readmeText.Contains('scaling-and-autoscaling.md')) {
        Add-Failure 'README.md: limitations must point at where the saturation measurement actually lives'
    }

    # 10.12 深入設定要收在 <details> 裡，維持第一屏精簡
    $detailsOpen = [regex]::Matches($readmeText, '<details>').Count
    $detailsClose = [regex]::Matches($readmeText, '</details>').Count
    if ($detailsOpen -lt 1) {
        Add-Failure 'README.md: expected the detailed setup content to live in collapsible <details> sections'
    }
    if ($detailsOpen -ne $detailsClose) {
        Add-Failure ('README.md: unbalanced <details> tags ({0} open, {1} close)' -f $detailsOpen, $detailsClose)
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host ('FAIL ({0} problem(s))' -f $failures.Count)
    foreach ($failure in $failures) {
        Write-Host ('  - {0}' -f $failure)
    }
    exit 1
}

Write-Host ('PASS: {0} portfolio documents verified against the current code and Compose configuration.' -f $documents.Count)
exit 0
