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

if (( failures > 0 )); then
  printf '%d test(s) failed\n' "$failures" >&2
  exit 1
fi

printf 'all demo-data library tests passed\n'
