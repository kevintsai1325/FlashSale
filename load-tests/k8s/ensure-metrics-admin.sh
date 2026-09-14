#!/usr/bin/env bash
# 確保 pod-request-counts.sh 需要的管理員帳號存在。
#
# 為什麼需要每次重建：load-tests/benchmark/fixtures.sql 會 TRUNCATE 包含 users 在內的
# 所有領域資料表，所以每次重新植入測試資料之後這個帳號就消失了。密碼經過 bcrypt，
# 無法直接寫進 SQL fixture，因此改為「用 API 註冊，再於資料庫提權」。
#
# 用法：
#   ADMIN_PASSWORD=... ./ensure-metrics-admin.sh

set -euo pipefail
export MSYS_NO_PATHCONV=1

NAMESPACE="${NAMESPACE:-flashsale}"
CONTEXT="${CONTEXT:-rancher-desktop}"
GATEWAY="${GATEWAY:-https://localhost:8443}"
ADMIN_EMAIL="${ADMIN_EMAIL:-metrics-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"
KUBECTL="${KUBECTL:-kubectl}"

k() { "$KUBECTL" --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

# 不用 -o /dev/null：MSYS_NO_PATHCONV=1 之下 /dev/null 會原樣傳給 Windows 版 curl，
# 它寫不進那個路徑而以 exit 23 失敗，在 set -e 下會讓整支腳本在提權前就中止。
status="$(curl -sk -X POST "$GATEWAY/api/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" \
  -w '\n%{http_code}' | tail -n1)"

# 201 = 新建，409/400 = 已存在。兩者都往下走，提權是冪等的。
case "$status" in
  201|400|409) ;;
  *) echo "unexpected register status $status for $ADMIN_EMAIL" >&2; exit 1 ;;
esac

k exec -i statefulset/postgres -- \
  psql -U flashsale -d flashsale -q -c \
  "UPDATE users SET role='ADMIN' WHERE email='$ADMIN_EMAIL';" >/dev/null

echo "metrics admin ready: $ADMIN_EMAIL (register status $status)"
