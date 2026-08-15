# FlashSale Week 5(可觀測性與 CI)設計規格

## 0. 關聯文件

本文件是 `docs/superpowers/specs/2026-08-08-flash-sale-design.md`(以下稱「主規格」)§11(API Audit
與統計)、§13(可觀測性與健康檢查)、§15 Week 5 範圍的細部設計。Week 1-4 已完成使用者側全部功能、
管理後台、API 稽核紀錄與通知重試(見
`docs/superpowers/plans/2026-08-15-week4-session-handoff.md`)。本輪把主規格 §13 列出、但一直
延後的可觀測性基礎設施(Actuator metrics、真正的分散式 tracing、結構化日誌)與 GitHub Actions CI
補齊,並把 Week 4 §3.3 提到的權宜 `TraceIdFilter` 換成 Micrometer Tracing。

## 1. 範圍

包含(對應主規格 §15 Week 5 範圍):

- Actuator health/liveness/readiness/metrics endpoint(不含 Prometheus registry,見 §7)
- 自訂搶購業務指標(庫存預扣成功/售罄/失敗次數、Redis Lua 預扣耗時)
- Micrometer Tracing(Brave)+ Zipkin,取代 Week 4 的 `TraceIdFilter`,涵蓋 HTTP、RabbitMQ
  producer/consumer 與排程背景工作
- Logback JSON 結構化日誌(console appender)
- GitHub Actions CI:backend(`./gradlew test`)與 frontend(`tsc -b` + vitest)兩個 job,
  build+test only,PR 與 push 到 `main` 時觸發

不包含(留給主規格 §2 第二階段或後續週次,§17 已明講):

- Prometheus/Grafana(第二階段;本輪只到 Micrometer 產生指標為止,不接 metrics scraper)
- CI 自動 build/push Docker image、CI 自動部署(本輪只驗證 build+test 通過,不含任何部署動作)
- CI 跑 k6(Week 4 spec §1 已寫死排除,理由不變:k6 需要一個活著的 stack,跟一般 CI unit/
  integration test 執行模型不同)
- 兩個延後到 Week 5 之後處理的項目(記錄在 Week 4 handoff 文件,本輪**仍不處理**,維持獨立):
  `ApiAuditFilter` 抓不到 401/403、`ApiAuditQueryService`/`AdminOrderQueryService` 直接注入 JPA
  repository 而非透過 port——這兩項是 Week 4 已知的架構債,跟本輪「可觀測性/CI」主題無關,不在
  這裡順便夾帶處理,避免範圍蔓延
- `frontend/src/features/auth/RequireAuth.tsx` 的 `isRestoring` 快速修法——同上,是 Week 1-3
  範圍的獨立小 bug,不屬於可觀測性/CI 主題,列入本文件 §9 作為本輪捎帶處理的獨立任務(成本低、
  跟其餘任務無依賴,值得跟本輪一起做完避免遺留第三份技術債清單)

## 2. 現狀盤點

- `management.endpoints.web.exposure.include` 目前只開 `health,info`(`application.yml`),沒有
  `metrics`。健康檢查沒有區分 liveness/readiness——`spring-boot-starter-data-redis`、
  `-amqp`、`-data-jpa` 都在 classpath 上,Spring Boot 已自動註冊對應的 `HealthIndicator`,只是
  没有啟用 probe 分組(`management.endpoint.health.probes.enabled`)去把它們分流到
  readiness/liveness。
- 目前**沒有任何自訂 Micrometer 指標**——`spring-boot-starter-actuator` 只提供框架自帶的
  HTTP/JVM/HikariCP 指標,搶購相關的預扣成功/售罄次數完全沒有被記錄,只能從 log 或資料庫反推。
- Week 4 的 `TraceIdFilter`(`common/web/TraceIdFilter.java`)只是產生一個 UUID 放進 MDC 與
  `X-Trace-Id` response header,**不是真正的分散式 tracing**——沒有 span 概念、RabbitMQ
  producer→consumer 之間完全斷開關聯、排程工作也不會有任何 trace 資訊。`ApiAuditFilter` 讀取
  `TraceIdFilter` 產生的值寫進 `api_audit_logs.trace_id`。
- `build.gradle.kts` 尚未有 `micrometer-tracing-bridge-brave`、`zipkin-reporter-brave`、
  `logstash-logback-encoder` 這幾個依賴。
- `logging` 設定目前只有 `logging.level.com.flashsale: INFO`(`application.yml`),沒有
  `logback-spring.xml`,走 Spring Boot 預設的純文字 console pattern。
- `docker-compose.yml` 目前有 postgres、redis、rabbitmq、mailpit、backend、frontend、nginx,
  沒有 Zipkin。
- `.github/` 目錄不存在,完全沒有 CI 設定。
- `frontend/package.json` 的 `scripts` 沒有 `test` 指令(`vitest` 只裝了套件,靠開發者自己打
  `npx vitest run`),CI 需要一個穩定的 script 入口。
- `RequireAdmin.tsx`(Week 4 新增)已經有 `isRestoring` 這個 flag 處理「頁面重新整理時
  `AuthContext.refresh()` 尚未 resolve、`isAuthenticated` 還是初始值」的問題;`RequireAuth.tsx`
  (Week 1-3 舊碼)還沒套用同樣兩行邏輯,會在重新整理時把已登入使用者誤導回 `/login`。

## 3. Actuator 與健康檢查

`application.yml` 調整:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
  health:
    readinessstate:
      enabled: true
    livenessstate:
      enabled: true
```

啟用 `probes.enabled` 後,Spring Boot 會自動把 health 分成 `livenessState`(只反映應用程式本身
是否需要重啟,不看外部依賴)與 `readinessState`(反映是否準備好接流量)兩組,且**自動把
PostgreSQL/Redis/RabbitMQ 既有的 `HealthIndicator` 併入 readiness 群組**——這是 Spring Boot
Actuator 的既有行為,不需要手寫任何 `HealthIndicator` 實作。`/actuator/health/liveness` 與
`/actuator/health/readiness` 兩個 sub-path 會自動出現。`show-details: when-authorized` 避免未
授權的呼叫者看到依賴細節(對應主規格 §11 資安取捨的一貫作法)。

`docker-compose.yml` 的 backend healthcheck 從目前打 `/actuator/health` 改成打
`/actuator/health/readiness`,更精確反映「依賴都就緒了才算活著」。

## 4. 自訂搶購業務指標

新增 `common/metrics/PurchaseMetrics`(`@Component`,建構子注入 `MeterRegistry`)。放在
`common` 而不是 `inventory/adapter/redis` 底下,是因為這個元件同時被 `inventory` 模組
(`RedisInventoryStockGateway`)跟 `order` 模組(`OrderPurchaseConsumer`)呼叫——寫進任何一個
模組自己的 `adapter` package 底下,另一個模組要呼叫它就會變成「跨模組直接依賴對方的 adapter
package」,ArchUnit 的 `modulesDoNotReachIntoOtherModulesAdapterPackages` 規則字面上不會抓到這個
情境(它只檢查非 adapter 類別依賴其他模組 adapter,adapter 對 adapter 不在檢查範圍內),但這正是
Week 4 handoff 文件記錄的 `ApiAuditQueryService`/`AdminOrderQueryService` 那種「規則沒涵蓋到但
實質上跨模組耦合」的同類問題,沒必要在本輪再種一個同樣性質的debt。放進 `common..`(比照既有的
`common.web`/`common.messaging`)兩個模組都能自由依賴,不需要新的 port 介面——這只是一個記錄
指標的橫切工具,不是業務邏輯,不需要走 hexagonal port/adapter 那一套。提供:

- `Counter reservationOutcome(String outcome)`:tag `outcome` 值為 `reserved` /
  `insufficient_stock`,對應 `StockReservationResult` 的兩種結果。
- `Timer reservationLatency`:包住整次 `reserve()` 呼叫(含可能的重新 seed),量測 Redis Lua
  預扣的耗時分布。

`RedisInventoryStockGateway.reserve()`(`backend/src/main/java/com/flashsale/inventory/adapter/
redis/RedisInventoryStockGateway.java:44`)改成呼叫 `PurchaseMetrics`,在既有邏輯外包一層計時與
計數,不改變原本的回傳值或例外行為——`ServiceUnavailableException` 那個分支不計入
`reservationOutcome`(那是基礎設施錯誤,不是業務結果),只在成功回傳 `RESERVED` /
`INSUFFICIENT_STOCK` 時記一次。

另外在 `OrderPurchaseConsumer.handle()`(`backend/src/main/java/com/flashsale/order/adapter/
messaging/OrderPurchaseConsumer.java:47`)成功建立訂單後呼叫
`PurchaseMetrics.orderCreated().increment()`,對應「Redis 預扣成功後,實際訂單有沒有真的建出來」
這個指標——跟 §1 排除的「Prometheus/Grafana 視覺化」無關,這幾個 counter/timer 本輪只需要能透過
`/actuator/metrics/{name}` 查得到即可,不需要儀表板。

不做:每個 API endpoint 的自訂指標(HTTP 層級的指標 Micrometer 框架本身已經提供,不需要重複造
輪子,對應主規格 §11「MVP 不建立自製 metrics framework」)。

## 5. Micrometer Tracing 與 Zipkin

### 5.1 依賴與設定

`build.gradle.kts` 新增:

```kotlin
implementation("io.micrometer:micrometer-tracing-bridge-brave")
implementation("io.zipkin.reporter2:zipkin-reporter-brave")
```

`application.yml` 新增:

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: http://zipkin:9411/api/v2/spans
spring:
  rabbitmq:
    template:
      observation-enabled: true
    listener:
      simple:
        observation-enabled: true
```

`sampling.probability: 1.0` 是作品集展示考量——每個請求都送 trace,面試官打開 Zipkin UI 就看得到
資料,不需要調高流量去撞抽樣機率。生產環境會是另一個數字,但那不是本輪要解決的問題。

### 5.2 移除 `TraceIdFilter`

`TraceIdFilter` 整個刪除。Spring Boot 3 在 classpath 上偵測到 `micrometer-tracing-bridge-brave`
後,會自動幫 Spring MVC 請求建立 span 並透過 Brave 的 `MDCScopeDecorator` 把 `traceId`/`spanId`
寫進 SLF4J MDC——這正是 `TraceIdFilter` 當初用土法煉鋼做的事,現在改用框架原生機制。

`ApiAuditFilter`(`backend/src/main/java/com/flashsale/common/web/ApiAuditFilter.java:68`)原本
讀 `TraceIdFilter.TRACE_ID_ATTRIBUTE` 的地方,改成呼叫注入的 `Tracer.currentSpan()` 取得
`traceId`,取不到(理論上不會發生,但防禦式處理)則存 null。`X-Trace-Id` response header 這個
行為保留,但改成從 `Tracer.currentSpan()` 取值寫回 header,方便前端/API 使用者除錯時人工關聯,
不依賴 Zipkin UI。

`api_audit_logs.request_id`/`trace_id`、`order_status_history` 的寫入邏輯不需要改動,只是資料
來源從 `TraceIdFilter` 的 UUID 換成 Brave 產生的真實 trace ID(格式不同但欄位語意不變)。

### 5.3 RabbitMQ 與排程背景工作的 trace 延續

`spring.rabbitmq.template.observation-enabled` / `listener.simple.observation-enabled` 這兩個
設定開啟後,Spring Boot 3.3 的 auto-configuration 會自動幫 `RabbitTemplate`(producer)與
`@RabbitListener`(consumer)包上 Micrometer Observation,讓 span 資訊透過訊息 header 傳遞——但
這只能串起 producer 跟 consumer 彼此之間的 trace,不會延續到更早觸發它們的 HTTP 請求。
`CreatePurchaseRequestService` 把 `CreateOrderRequestedEvent` 寫進 outbox 這一步,是在該次 HTTP
請求自己的 trace 底下;真正把訊息送出去的 `OutboxPublisher.publishPending()` 是 `@Scheduled`
方法,沒有上游 span,所以它呼叫 `rabbitTemplate.send()` 時起的是一條全新的 trace,
`OrderPurchaseConsumer.handle()` 收到訊息後掛的 span 屬於這條新 trace。也就是說,outbox 寫入
(HTTP 請求 trace)跟後續的送出+消費(另一條獨立 trace)在 Zipkin 上會是兩條不同的 trace,不是
同一條——`observation-enabled` 只保證 producer/consumer 這一段本身乾淨地串成一條 trace,不代表
它跟最初的 HTTP 請求 trace 相連。

四個排程(`PaymentTimeoutScheduler.expireOverduePayments()`、
`InventoryReconciliationScheduler`、`NotificationRetryScheduler`、`ApiAuditRetentionScheduler`)
沒有上游請求觸發,天生不會有 trace。這四個排程方法補上 `@Observed(name = "scheduler.<name>")`
註解(需要 `ObservedAspect` bean,`micrometer-tracing-bridge-brave` 引入後手動註冊一個
`@Bean ObservedAspect observedAspect(ObservationRegistry registry)` 即可,Spring Boot 不會自動
註冊這個 bean),讓每次排程執行都起一條獨立的 trace,在 Zipkin 上看得到「這次排程跑了多久、
處理了什麼」,在結構化 log 裡也會帶上這次執行專屬的 `traceId`,方便把同一次排程執行的所有 log
行串起來看。

### 5.4 docker-compose 新增 Zipkin

```yaml
zipkin:
  image: openzipkin/zipkin:3
  ports:
    - "9411:9411"
```

官方 image,不寫 Dockerfile。只給本機 `docker compose up` 後想看 trace 瀑布圖的使用者用,不透過
Nginx 反向代理(不是給外部流量存取的服務,跟主規格 §17「backend/frontend port 不對外暴露」的
考量一致——這裡直接沿用「只有 Nginx 對外」的既有原則,Zipkin 本身也只在本機 debug 用,不需要
額外加一層反代)。

## 6. 結構化 JSON 日誌

`build.gradle.kts` 新增 `implementation("net.logstash.logback:logstash-logback-encoder:7.4")`。

新增 `backend/src/main/resources/logback-spring.xml`,單一 console appender,用
`LogstashEncoder` 輸出 JSON,欄位包含 timestamp、level、logger、thread、message、MDC(此時已經
自動帶有 `traceId`/`spanId`,見 §5.2)、例外的 stack trace(以字串欄位呈現,不额外拆解)。不做
profile 分流(本機 IDE 執行跟 docker compose 執行都是同一份 JSON 輸出)——`docker compose logs`
是這個專案主要的 log 查閱方式,單一格式維持簡單,開發者本機想要好讀格式可以自行接 `| jq`,不在
應用程式裡面為了兩種閱讀情境維護兩份 encoder 設定。

`logging.level.com.flashsale: INFO` 這行設定保留,只是原本走 Spring Boot 預設 pattern 的部分
現在改由 `logback-spring.xml` 接手。

## 7. 為什麼不接 Prometheus registry

主規格 §17 與 Week 4 spec §1 都已經把 Prometheus/Grafana 明確列為第二階段。本輪只到「Micrometer
產生指標、可以透過 `/actuator/metrics/{name}` 用 HTTP 直接查詢」為止,不加
`micrometer-registry-prometheus` 依賴、不加 `/actuator/prometheus` endpoint。這不是遺漏,是
維持主規格既定範圍——多裝一個 registry 依賴本身成本不高,但「裝了 Prometheus 格式的 endpoint
卻沒有 Prometheus 去 scrape」對面試官來說是一個看起來做一半的訊號,不如等第二階段有真正的
Prometheus/Grafana 时一起做完整。

## 8. GitHub Actions CI

新增 `.github/workflows/ci.yml`,觸發條件 `push: branches: [main]` 與
`pull_request: branches: [main]`,兩個獨立 job(平行執行):

**`backend` job**:`actions/checkout` → `actions/setup-java`(Temurin 21)→
`actions/setup-gradle`(內建 Gradle 快取)→ `./gradlew test`。GitHub-hosted `ubuntu-latest`
runner 預裝 Docker,Testcontainers(PostgreSQL/Redis/RabbitMQ)不需要額外的 service container
設定就能跑,這點沿用 Week 4 handoff 文件記錄過的本機開發經驗(本機也是靠 Docker Desktop 讓
Testcontainers 連得到,CI runner 原生就有 Docker daemon,不需要 npipe 那些本機限定的變通)。
`./gradlew test` 已經涵蓋 unit/application/integration/API/ArchUnit 全部測試(Week 4 建立的
`ArchitectureTest` 也在裡面),不需要額外的 CI-only 測試指令。

**`frontend` job**:`actions/checkout` → `actions/setup-node`(Node 20,搭配 `cache: npm`)→
`npm ci` → `npm run lint` → `npm run build`(`tsc -b && vite build`,型別檢查跟建置一次做完)→
`npm test`。`package.json` 新增 `"test": "vitest run"` script(目前沒有這個入口,CI 需要一個
穩定指令,順手補上,本機開發者也能直接用同一個指令跑一次性測試而不是只能背 `npx vitest run`)。

不做:兩個 job 之間沒有相依關係,不需要 matrix build(只有一組 Java/Node 版本),不快取
Testcontainers 拉下來的 image(GitHub Actions 的 layer cache 對這個場景效益不明顯,增加設定
複雜度換取的收益太小)。

## 9. 捎帶處理:`RequireAuth.tsx` 的 `isRestoring` 修法

`frontend/src/features/auth/RequireAuth.tsx` 套用跟 `RequireAdmin.tsx` 相同的 `isRestoring`
判斷(`AuthContext` 的 `isRestoring` flag 為 true 時,不要提前導向 `/login`,等
`refresh()` resolve 後再依真正的 `isAuthenticated` 決定)。這是 Week 4 final review 就發現、記錄
在 handoff 文件裡的既有 bug,不是本輪新增的範圍,但改動成本是既有邏輯的複製(兩行),沒有依賴
本文件其餘任何一個可觀測性/CI 任務,獨立作為 plan 裡的一個任務執行,避免留下第三份「已知但沒修」
的技術債清單。

## 10. 測試策略

- `PurchaseMetrics` 補計數/計時邏輯:用 `SimpleMeterRegistry`(Micrometer 測試用的記憶體
  registry,不需要真的接 Zipkin/Prometheus)驗證 `RedisInventoryStockGateway.reserve()` 呼叫後
  對應 counter 增加、timer 有記錄。
- Tracing/Zipkin 整合本身**不新增自動化測試**——這是框架自動裝配的行為(Spring Boot
  auto-configuration + Brave),用 Testcontainers 起一個假 Zipkin 來驗證「span 真的送到了」
  投入產出比低,改用手動驗證(啟動整個 docker compose stack,實際打幾個 API,到 Zipkin UI 確認
  trace 出現、到 RabbitMQ 訊息流程確認 producer/consumer 在同一條 trace 下)並在 README/plan
  ledger 記錄操作過程與結果截圖,取代自動化測試。
- 結構化 JSON log 格式**不新增自動化測試**——沒有程式碼在解析自己輸出的 log,加測試只是驗證
  logback 設定檔語法正確,價值有限;改成本機啟動後手動確認 `docker compose logs backend` 輸出
  合法 JSON(可以用 `jq` 驗證能否解析)。
- GitHub Actions workflow 本身透過**實際跑一次 CI**驗證(推上一個分支或開 PR 觸發),不透過本地
  YAML linter 之類的間接驗證方式;plan 裡會有一個任務是「push 一個分支確認 workflow 真的綠燈」。
- `RequireAuth.tsx` 修法:比照 `RequireAdmin.test.tsx` 應該已有的「刷新頁面時不應該被踢出」測試
  案例(若存在就複製一份改成 `RequireAuth` 的情境;若原本沒有這個測試案例,兩邊一起補)。

## 11. 關鍵取捨

- Zipkin 只做本機 debug 用途,不接 Nginx 反代、不對外暴露——跟主規格 §17「只有 Nginx 對外」的
  既有安全取捨一致,新增一個服務不代表要放寬既有邊界。
- 結構化日誌只做 console JSON,不做 profile 分流的雙格式——避免為了「本機讀起來爽」這個次要
  需求增加設定維護成本,符合主規格 §17「套件優先但不濫用抽象」。
- CI 只做 build+test,不做 image build/push/deploy——完成條件 §16 只要求「GitHub Actions
  能自動執行必要驗證」,沒有要求自動部署,作品集展示的重點是「有自動化驗證」這件事本身,不是
  真的要有一個自動部署的環境。
- 排程背景工作用 `@Observed` 手動起 trace,而不是想辦法「延續」到觸發它的某條 trace——排程本來
  就沒有觸發它的上游請求(是 cron,不是任何使用者動作的延伸),硬要接到某條 trace 上是假造因果
  關係,獨立成一條新 trace 才是誠實反映實際執行模型。
