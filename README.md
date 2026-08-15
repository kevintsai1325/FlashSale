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
