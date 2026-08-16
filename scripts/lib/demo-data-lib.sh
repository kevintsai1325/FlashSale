#!/usr/bin/env bash

readonly DEMO_USER_EMAIL='demo.user@example.test'
readonly DEMO_ADMIN_EMAIL='demo.admin@example.test'
readonly DEMO_PRODUCT_NAME='[DEMO] Portfolio Product'
readonly DEMO_IDENTIFIER_PREFIX='DEMO-PORTFOLIO-'
readonly DEMO_USER_TRACE_ID='d3e0f001000000000000000000000001'
readonly DEMO_ADMIN_TRACE_ID='d3e0f002000000000000000000000002'

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

require_demo_base_url() {
  case "${1:-}" in
    'https://localhost:8443'|'https://127.0.0.1:8443') return 0 ;;
    *)
      printf 'refusing non-local demo base URL: %s\n' "${1:-<empty>}" >&2
      return 1
      ;;
  esac
}

require_compose_project_name() {
  case "${1:-}" in
    ''|flashsale) return 0 ;;
    *)
      printf 'refusing Compose project override: %s\n' "$1" >&2
      return 1
      ;;
  esac
}

normalize_compose_path() {
  local value="${1:-}"
  value="${value//\\//}"
  value="${value%/}"
  printf '%s\n' "$value"
}

require_compose_identity() {
  local project="$1"
  local config_file working_dir expected_root expected_config
  config_file="$(normalize_compose_path "$2")"
  working_dir="$(normalize_compose_path "$3")"
  expected_root="$(normalize_compose_path "$4")"
  expected_config="${expected_root}/compose.yaml"

  if [[ "$project" != 'flashsale' ]]; then
    printf 'refusing unexpected Compose project label: %s\n' "$project" >&2
    return 1
  fi
  if [[ "$config_file" != "$expected_config" ]]; then
    printf 'refusing unexpected Compose config file: %s\n' "$config_file" >&2
    return 1
  fi
  if [[ "$working_dir" != "$expected_root" ]]; then
    printf 'refusing unexpected Compose working directory: %s\n' "$working_dir" >&2
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

demo_seed_advisory_lock_sql() {
  printf '%s\n' 'SELECT pg_advisory_xact_lock(748395021);'
}

demo_traceparent_for_email() {
  case "${1:-}" in
    "$DEMO_USER_EMAIL")
      printf '00-%s-d3e0f00100000001-01\n' "$DEMO_USER_TRACE_ID"
      ;;
    "$DEMO_ADMIN_EMAIL")
      printf '00-%s-d3e0f00200000002-01\n' "$DEMO_ADMIN_TRACE_ID"
      ;;
    *)
      printf 'refusing traceparent for non-demo email: %s\n' "${1:-<empty>}" >&2
      return 1
      ;;
  esac
}

require_redis_del_reply() {
  local reply="${1//$'\r'/}"
  case "$reply" in
    0|1) return 0 ;;
    *)
      printf 'Redis DEL failed or returned an unexpected reply: %s\n' "${reply:-<empty>}" >&2
      return 1
      ;;
  esac
}

require_redis_set_reply() {
  local reply="${1//$'\r'/}"
  if [[ "$reply" != 'OK' ]]; then
    printf 'Redis SET failed or returned an unexpected reply: %s\n' "${reply:-<empty>}" >&2
    return 1
  fi
}
