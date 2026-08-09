#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout localhost.key -out localhost.crt \
  -days 365 -subj "/CN=localhost"
echo "Generated nginx/certs/localhost.crt and localhost.key (gitignored)."
