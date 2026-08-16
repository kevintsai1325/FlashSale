#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_PATH="${SCRIPT_DIR}/../lib/demo-data-lib.sh"

if [[ ! -f "$LIB_PATH" ]]; then
  printf 'FAIL: demo data library does not exist: %s\n' "$LIB_PATH" >&2
  exit 1
fi

# shellcheck source=../lib/demo-data-lib.sh
source "$LIB_PATH"

failures=0

fail() {
  printf 'not ok - %s\n' "$1"
  failures=$((failures + 1))
}

pass() {
  printf 'ok - %s\n' "$1"
}

assert_failure_contains() {
  local name="$1"
  local expected="$2"
  shift 2
  local output status

  if output="$({ "$@"; } 2>&1)"; then
    status=0
  else
    status=$?
  fi

  if (( status == 0 )); then
    fail "$name (expected non-zero status)"
  elif [[ "$output" != *"$expected"* ]]; then
    fail "$name (expected output to contain: $expected)"
  else
    pass "$name"
  fi
}

assert_output_equals() {
  local name="$1"
  local expected="$2"
  shift 2
  local output status

  if output="$({ "$@"; } 2>&1)"; then
    status=0
  else
    status=$?
  fi

  if (( status != 0 )); then
    fail "$name (expected zero status, got $status: $output)"
  elif [[ "$output" != "$expected" ]]; then
    fail "$name (expected '$expected', got '$output')"
  else
    pass "$name"
  fi
}

empty_passwords_fail() {
  unset DEMO_USER_PASSWORD DEMO_ADMIN_PASSWORD
  require_demo_passwords
}

empty_admin_password_fails() {
  DEMO_USER_PASSWORD='local-user-password'
  DEMO_ADMIN_PASSWORD=''
  export DEMO_USER_PASSWORD DEMO_ADMIN_PASSWORD
  require_demo_passwords
}

assert_failure_contains \
  'empty demo passwords fail closed' \
  'DEMO_USER_PASSWORD must be set for local demo data' \
  empty_passwords_fail

assert_failure_contains \
  'empty admin password fails closed' \
  'DEMO_ADMIN_PASSWORD must be set for local demo data' \
  empty_admin_password_fails

assert_failure_contains \
  'unsupported commands fail closed' \
  'unsupported command: destroy' \
  require_supported_command destroy

assert_failure_contains \
  'cleanup rejects a non-demo email' \
  'refusing non-demo email' \
  require_demo_email person@example.test

assert_failure_contains \
  'cleanup rejects a non-demo product name' \
  'refusing non-demo product name' \
  require_demo_product_name 'Portfolio Product'

assert_failure_contains \
  'cleanup rejects a non-demo identifier prefix' \
  'refusing non-demo identifier prefix' \
  require_demo_prefix 'PORTFOLIO-'

assert_failure_contains \
  'cleanup rejects a non-flashsale database' \
  'refusing cleanup outside database flashsale' \
  require_flashsale_database postgres

assert_output_equals \
  'localhost demo URL is accepted' \
  '' \
  require_demo_base_url 'https://localhost:8443'

assert_output_equals \
  'loopback demo URL is accepted' \
  '' \
  require_demo_base_url 'https://127.0.0.1:8443'

assert_failure_contains \
  'demo URL rejects userinfo' \
  'refusing non-local demo base URL' \
  require_demo_base_url 'https://user@localhost:8443'

assert_failure_contains \
  'demo URL rejects paths' \
  'refusing non-local demo base URL' \
  require_demo_base_url 'https://localhost:8443/api'

assert_failure_contains \
  'demo URL rejects other hosts' \
  'refusing non-local demo base URL' \
  require_demo_base_url 'https://example.test:8443'

assert_output_equals \
  'empty Compose project override is accepted' \
  '' \
  require_compose_project_name ''

assert_output_equals \
  'flashsale Compose project override is accepted' \
  '' \
  require_compose_project_name flashsale

assert_failure_contains \
  'foreign Compose project override is rejected' \
  'refusing Compose project override' \
  require_compose_project_name production

assert_output_equals \
  'matching Compose labels are accepted' \
  '' \
  require_compose_identity \
    flashsale \
    'C:\SideProject\FlashSale\compose.yaml' \
    'C:\SideProject\FlashSale' \
    'C:\SideProject\FlashSale\.env' \
    'C:/SideProject/FlashSale'

assert_failure_contains \
  'foreign Compose config label is rejected' \
  'refusing unexpected Compose config file' \
  require_compose_identity \
    flashsale \
    'C:\Other\compose.yaml' \
    'C:\SideProject\FlashSale' \
    'C:\SideProject\FlashSale\.env' \
    'C:/SideProject/FlashSale'

assert_failure_contains \
  'foreign Compose working directory is rejected' \
  'refusing unexpected Compose working directory' \
  require_compose_identity \
    flashsale \
    'C:\SideProject\FlashSale\compose.yaml' \
    'C:\Other' \
    'C:\SideProject\FlashSale\.env' \
    'C:/SideProject/FlashSale'

assert_output_equals \
  'canonical Compose and env targets are accepted' \
  '' \
  require_canonical_compose_targets \
    'C:/SideProject/FlashSale/compose.yaml' \
    'C:/SideProject/FlashSale/.env' \
    'C:/SideProject/FlashSale'

assert_failure_contains \
  'foreign Compose file override is rejected' \
  'refusing foreign Compose file override' \
  require_canonical_compose_targets \
    'C:/Other/compose.yaml' \
    'C:/SideProject/FlashSale/.env' \
    'C:/SideProject/FlashSale'

assert_failure_contains \
  'foreign env file override is rejected' \
  'refusing foreign env file override' \
  require_canonical_compose_targets \
    'C:/SideProject/FlashSale/compose.yaml' \
    'C:/Other/.env' \
    'C:/SideProject/FlashSale'

assert_output_equals \
  'audit predicate includes authenticated users and exact traces' \
  "(user_id IN (12,13) OR trace_id IN ('d3e0f001000000000000000000000001','d3e0f002000000000000000000000002'))" \
  demo_audit_predicate '12,13'

assert_failure_contains \
  'audit predicate rejects non-numeric user ids' \
  'refusing invalid demo user ids' \
  demo_audit_predicate '12,admin'

assert_output_equals \
  'registration audit sweep SQL uses only exact trace literals' \
  "DELETE FROM api_audit_logs WHERE trace_id IN ('d3e0f001000000000000000000000001','d3e0f002000000000000000000000002');" \
  registration_audit_delete_sql

assert_output_equals \
  'SQL literals escape single quotes' \
  "O''Reilly" \
  sql_escape_literal "O'Reilly"

assert_output_equals \
  'JSON strings escape quotes and backslashes' \
  'local-\"demo\"-\\password' \
  json_escape_string 'local-"demo"-\password'

assert_output_equals \
  'order deletion predicate uses the exact demo prefix' \
  "order_no LIKE 'DEMO-PORTFOLIO-%'" \
  demo_prefix_predicate order_no

assert_output_equals \
  'activity deletion predicate uses the exact demo prefix' \
  "request_id LIKE 'DEMO-PORTFOLIO-%'" \
  demo_prefix_predicate request_id

assert_output_equals \
  'activity seed predicate rejects an existing product activity' \
  'NOT EXISTS (SELECT 1 FROM flash_sales WHERE product_id = products.id)' \
  demo_activity_absence_predicate

assert_output_equals \
  'seed transaction uses a fixed advisory lock' \
  'SELECT pg_advisory_xact_lock(748395021);' \
  demo_seed_advisory_lock_sql

assert_output_equals \
  'demo user gets a fixed valid traceparent' \
  '00-d3e0f001000000000000000000000001-d3e0f00100000001-01' \
  demo_traceparent_for_email "$DEMO_USER_EMAIL"

assert_output_equals \
  'demo admin gets a distinct fixed valid traceparent' \
  '00-d3e0f002000000000000000000000002-d3e0f00200000002-01' \
  demo_traceparent_for_email "$DEMO_ADMIN_EMAIL"

assert_failure_contains \
  'Redis delete rejects error replies' \
  'Redis DEL failed' \
  require_redis_del_reply ERR

assert_output_equals \
  'Redis delete accepts a missing exact key' \
  '' \
  require_redis_del_reply 0

assert_output_equals \
  'Redis SET accepts OK' \
  '' \
  require_redis_set_reply OK

if (( failures > 0 )); then
  printf '%d test(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'all demo-data library tests passed\n'
