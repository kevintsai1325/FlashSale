#!/usr/bin/env bash
set -euo pipefail

CERT_DIR=/etc/nginx/certs
if [ ! -f "$CERT_DIR/localhost.crt" ] || [ ! -f "$CERT_DIR/localhost.key" ]; then
    echo "No certs found in $CERT_DIR, generating self-signed cert..."
    CERT_DIR="$CERT_DIR" generate-cert.sh
fi

exec "$@"
