#!/usr/bin/env bash

readonly DEMO_USER_EMAIL='demo.user@example.test'
readonly DEMO_ADMIN_EMAIL='demo.admin@example.test'
readonly DEMO_PRODUCT_NAME='[DEMO] Portfolio Product'
readonly DEMO_IDENTIFIER_PREFIX='DEMO-PORTFOLIO-'

require_demo_passwords() {
  if [[ -z "${DEMO_USER_PASSWORD:-}" ]]; then
    printf 'DEMO_USER_PASSWORD must be set for local demo data\n' >&2
    return 1
  fi
  if [[ -z "${DEMO_ADMIN_PASSWORD:-}" ]]; then
    printf 'DEMO_ADMIN_PASSWORD must be set for local demo data\n' >&2
    return 1
  fi
}

require_supported_command() {
  case "${1:-}" in
    seed|cleanup) return 0 ;;
    *)
      printf 'unsupported command: %s (expected seed or cleanup)\n' "${1:-<empty>}" >&2
      return 1
      ;;
  esac
}

require_demo_email() {
  case "${1:-}" in
    "$DEMO_USER_EMAIL"|"$DEMO_ADMIN_EMAIL") return 0 ;;
    *)
      printf 'refusing non-demo email: %s\n' "${1:-<empty>}" >&2
      return 1
      ;;
  esac
}

require_demo_product_name() {
  if [[ "${1:-}" != "$DEMO_PRODUCT_NAME" ]]; then
    printf 'refusing non-demo product name: %s\n' "${1:-<empty>}" >&2
    return 1
  fi
}

require_demo_prefix() {
  if [[ "${1:-}" != "$DEMO_IDENTIFIER_PREFIX" ]]; then
    printf 'refusing non-demo identifier prefix: %s\n' "${1:-<empty>}" >&2
    return 1
  fi
}

require_flashsale_database() {
  if [[ "${1:-}" != 'flashsale' ]]; then
    printf 'refusing cleanup outside database flashsale (got: %s)\n' "${1:-<empty>}" >&2
    return 1
  fi
}

sql_escape_literal() {
  local value="${1-}"
  printf '%s\n' "${value//\'/\'\'}"
}

json_escape_string() {
  local value="${1-}"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  value="${value//$'\t'/\\t}"
  printf '%s\n' "$value"
}

demo_prefix_predicate() {
  local column="${1:-}"
  case "$column" in
    order_no|request_id|idempotency_key|message_id|aggregate_id|simulated_transaction_id) ;;
    *)
      printf 'refusing unsupported cleanup column: %s\n' "${column:-<empty>}" >&2
      return 1
      ;;
  esac

  require_demo_prefix "$DEMO_IDENTIFIER_PREFIX" || return 1
  printf "%s LIKE '%s%%'\n" "$column" "$DEMO_IDENTIFIER_PREFIX"
}

demo_activity_absence_predicate() {
  printf '%s\n' 'NOT EXISTS (SELECT 1 FROM flash_sales WHERE product_id = products.id)'
}
