#!/usr/bin/env bash
set -euo pipefail
CERT_DIR="${CERT_DIR:-$(cd "$(dirname "$0")" && pwd)}"
cd "$CERT_DIR"
openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout localhost.key -out localhost.crt \
  -days 365 -subj "/CN=localhost"
echo "Generated $CERT_DIR/localhost.crt and localhost.key (gitignored)."
