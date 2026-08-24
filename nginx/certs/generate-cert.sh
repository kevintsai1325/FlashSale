#!/usr/bin/env bash
set -euo pipefail
CERT_DIR="${CERT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
cd "$CERT_DIR"
# 瀏覽器從 Chrome 58 起就不看 CN 了，沒有 SAN 一律 ERR_CERT_COMMON_NAME_INVALID。
# EC2 上用 CERT_SAN 帶入該台的公有 IP。
SAN="${CERT_SAN:-DNS:localhost,IP:127.0.0.1}"
openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout localhost.key -out localhost.crt \
  -days 365 -subj "/CN=localhost" -addext "subjectAltName=${SAN}"
echo "Generated $CERT_DIR/localhost.crt and localhost.key (gitignored, SAN=${SAN})."
