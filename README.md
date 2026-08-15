# FlashSale

## Running locally (Docker Compose)

The stack is fronted by an Nginx reverse proxy that terminates TLS and is the
only service exposed on the host. All other services (`backend`, `frontend`,
`postgres`, `redis`, `rabbitmq`, `mailpit`) are reachable only on the internal
Docker network.

### 1. Configure environment secrets

Copy the example env file:

```bash
cp .env.example .env
```

The backend will not start without `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` set —
there is no default. Generate a local RSA key pair for JWT signing:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/jwt-private.pem
openssl rsa -in /tmp/jwt-private.pem -pubout -out /tmp/jwt-public.pem
```

Then paste each file's full contents (including the `-----BEGIN...-----` /
`-----END...-----` lines) into `.env` as a double-quoted, multi-line value:

```
JWT_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC...
-----END PRIVATE KEY-----"
JWT_PUBLIC_KEY="-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuGPTwoa...
-----END PUBLIC KEY-----"
```

`GMAIL_USERNAME`/`GMAIL_APP_PASSWORD` can stay empty for local development —
registration emails are caught by the bundled Mailpit service instead of
being sent via real Gmail SMTP (see step 4).

`.env` is gitignored — never commit it.

### 2. Generate a local TLS certificate

Nginx expects a self-signed certificate at `nginx/certs/localhost.crt` /
`nginx/certs/localhost.key`. Generate it once:

```bash
chmod +x nginx/certs/generate-cert.sh
./nginx/certs/generate-cert.sh
```

This writes `localhost.crt` and `localhost.key` into `nginx/certs/` (both are
gitignored — never commit them).

> **Git Bash on Windows**: the script's `/CN=localhost` argument gets mangled
> into a filesystem path by MSYS's automatic path conversion, causing an
> `openssl` error. Prefix the command with `MSYS_NO_PATHCONV=1` if you hit
> `req: subject name is expected to be in the format ...`.

### 3. Start the stack

```bash
docker compose up --build -d
```

### 4. Viewing sent emails (Mailpit)

Mailpit isn't published to the host, so `http://localhost:8025` won't work
directly from a browser. Check delivered emails via the running container
instead:

```bash
docker compose exec mailpit sh -c "wget -qO- http://localhost:8025/api/v1/messages"
```

### 5. Trust / bypass the self-signed certificate

The certificate is self-signed, so browsers and `curl` will warn about it.

- **curl**: pass `-k` to skip verification, e.g. `curl -k https://localhost:8443/`.
- **Browser**: visiting `https://localhost:8443` will show a security warning;
  proceed past it for local development, or import `nginx/certs/localhost.crt`
  into your OS/browser trust store to silence the warning.

> This is a local-development stub. Trusting a real CA-signed certificate (or
> a proper local CA) is out of scope for Week 1 and will be expanded in a
> later week's tasks.

All host traffic goes through `https://localhost:8443` — there is no other
exposed port.

## 管理後台 (Admin)

前端有一組 `/admin/*` 路由(儀表板、訂單列表、API 稽核紀錄、通知管理),僅
`ADMIN` 角色可以進入;一般 `USER` 或未登入者會被導回首頁。

### 將帳號升級為 ADMIN

先透過一般註冊流程建立帳號並登入一次,再直接對 Postgres 執行 SQL,把
`users` 資料表中該帳號的 `role` 欄位改成 `ADMIN`:

```bash
docker compose exec postgres psql -U flashsale -d flashsale \
  -c "UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';"
```

`role` 是 JWT access token 簽發時就寫死的 claim,所以升級後**必須重新登入**
一次,新的 token 才會帶有 `ROLE_ADMIN` 權限。

### 進入管理後台

重新登入後,瀏覽器打開 `https://localhost:8443/admin` 即可看到管理後台。

### 壓力測試

`load-tests/` 目錄下有一支 k6 壓力測試腳本,操作方式與資料準備請參考
[`load-tests/README.md`](load-tests/README.md)。

## 可觀測性 (Observability)

- **Actuator**:`/actuator/health/liveness`、`/actuator/health/readiness` 兩個端點目前只給
  容器內部使用(`compose.yaml` 的 backend healthcheck 打 `http://localhost:8080/actuator/health/readiness`),
  沒有透過 Nginx 對外反代——`nginx/nginx.conf` 沒有 `/actuator/` 的 location 規則,本機除錯可以用
  `docker compose exec backend wget -qO- http://localhost:8080/actuator/health/liveness`。
  `/actuator/metrics` 系列端點需要 `ADMIN` 角色的 JWT(比照後台 API 的授權方式),同樣只在容器
  內部或直接對 backend 發請求時可用。
- **Zipkin**:`http://localhost:9411/zipkin/`(只在本機 debug 用,沒有透過 Nginx 反代,不對外
  暴露)。每個 HTTP 請求都會產生一條獨立的 trace;RabbitMQ producer/consumer(outbox 送出到
  `OrderPurchaseConsumer` 處理)透過 `observation-enabled` 正確串成另一條獨立的 trace,但因為
  outbox 沒有持久化 trace context,這條訊息 trace 跟原本觸發它的 HTTP 請求 trace 是**分開**的
  兩條,不是同一條;每個排程背景工作也各自起一條獨立 trace。要串成從進站到訂單建立的完整
  呼叫鏈,需要在 `outbox_events` 加一欄存 trace context 並手動傳遞,目前是已知限制,列為未來
  工作。
- **結構化日誌**:`docker compose logs backend` 輸出的每一行都是 JSON,可以用 `jq` 過濾/解析,
  每一行都帶有 `traceId`/`spanId`,可以拿 Zipkin 上看到的 trace id 回頭到 log 裡搜尋同一次
  請求的完整處理過程。
- **自訂搶購指標**:`purchase.reservation`(tag `outcome=reserved|insufficient_stock`)、
  `purchase.reservation.latency`、`purchase.order.created` 這三個 Micrometer 指標可以透過
  `/actuator/metrics/{name}` 查詢,反映 Redis 預扣成功/售罄次數與延遲分布。

## CI

[![CI](https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml/badge.svg)](https://github.com/kevintsai1325/FlashSale/actions/workflows/ci.yml)

GitHub Actions 在每次 push 到 `main` 或開 PR 時,平行執行 backend(`./gradlew test`,涵蓋
unit/application/integration/API/ArchUnit 測試)與 frontend(lint、型別檢查、build、
vitest)兩個 job。CI 只驗證 build+test 通過,不包含映像檔建置/推送/部署。
