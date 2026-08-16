# FlashSale 作品集文件契約測試 / portfolio documentation contract test.
#
# 執行方式 (Windows PowerShell 5.1):
#   powershell.exe -File scripts/tests/portfolio-docs-test.ps1
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
    $javaRoot = Join-Path $repoRoot 'backend\src\main\java'
    $controllers = Get-ChildItem -LiteralPath $javaRoot -Recurse -Filter '*Controller.java'
    foreach ($controller in $controllers) {
        $source = Read-TextFile -Path $controller.FullName
        $base = ''
        $classMapping = [regex]::Match($source, '@RequestMapping\(\s*(?:value\s*=\s*)?"([^"]*)"')
        if ($classMapping.Success) {
            $base = $classMapping.Groups[1].Value
            # class 層級的 @RequestMapping 本身也是文件中合理的「這組 API 的基底路徑」寫法。
            $templates.Add((ConvertTo-RouteTemplate -Path $base)) | Out-Null
        }
        $methodMappings = [regex]::Matches($source, '@(?:Get|Post|Put|Patch|Delete)Mapping\(\s*(?:value\s*=\s*)?(?:"([^"]*)")?')
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

$metricsSource = Read-TextFile -Path (Join-Path $repoRoot 'backend\src\main\java\com\flashsale\common\metrics\PurchaseMetrics.java')

foreach ($documentName in $documents.Keys) {
    $documentPath = Join-Path $portfolioDir $documentName
    if (-not (Test-Path -LiteralPath $documentPath)) {
        Add-Failure ('missing document: docs/portfolio/{0}' -f $documentName)
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
    if ($documentName -eq 'architecture.md' -and $mermaidBlocks -lt 2) {
        Add-Failure ('architecture.md: expected at least 2 mermaid diagrams, found {0}' -f $mermaidBlocks)
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
    $metricMatches = [regex]::Matches($text, 'purchase\.[a-z]+(?:\.[a-z]+)*')
    foreach ($metricMatch in $metricMatches) {
        if (-not $metricsSource.Contains($metricMatch.Value)) {
            Add-Failure ('{0}: metric name not found in PurchaseMetrics.java: {1}' -f $documentName, $metricMatch.Value)
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
