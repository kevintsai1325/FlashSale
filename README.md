# FlashSale

## Running locally (Docker Compose)

The stack is fronted by an Nginx reverse proxy that terminates TLS and is the
only service exposed on the host. All other services (`backend`, `frontend`,
`postgres`, `redis`, `rabbitmq`, `mailpit`) are reachable only on the internal
Docker network.

### 1. Generate a local TLS certificate

Nginx expects a self-signed certificate at `nginx/certs/localhost.crt` /
`nginx/certs/localhost.key`. Generate it once:

```bash
chmod +x nginx/certs/generate-cert.sh
./nginx/certs/generate-cert.sh
```

This writes `localhost.crt` and `localhost.key` into `nginx/certs/` (both are
gitignored — never commit them).

### 2. Start the stack

```bash
docker compose up --build -d
```

### 3. Trust / bypass the self-signed certificate

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
