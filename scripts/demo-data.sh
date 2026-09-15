#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd -P)"

# shellcheck source=lib/demo-data-lib.sh
source "${SCRIPT_DIR}/lib/demo-data-lib.sh"

DEMO_BASE_URL="${DEMO_BASE_URL:-https://localhost:8443}"
DEMO_COMPOSE_FILE="${DEMO_COMPOSE_FILE:-${REPO_ROOT}/compose.yaml}"
DEMO_ENV_FILE="${DEMO_ENV_FILE:-${REPO_ROOT}/.env}"
readonly DEMO_BASE_URL DEMO_COMPOSE_FILE DEMO_ENV_FILE REPO_ROOT

COMPOSE=(docker compose -f "${REPO_ROOT}/compose.yaml" --env-file "${REPO_ROOT}/.env")

compose() {
  "${COMPOSE[@]}" "$@"
}

psql_exec() {
  compose exec -T postgres psql \
    --no-psqlrc -X -v ON_ERROR_STOP=1 -U flashsale -d flashsale "$@"
}

# P4 步驟 2：purchase-service 有自己的資料庫。示範資料的清理因此要分兩次打，
# 而且**兩次之間沒有交易**——跨資料庫的原子性不存在，這是拆庫實實在在的代價。
# 順序上先清 purchase 再清 platform：反過來的話 platform 的列先沒了，
# purchase 這邊就查不出哪些是示範資料。
psql_purchase_exec() {
  compose exec -T postgres-purchase psql \
    --no-psqlrc -X -v ON_ERROR_STOP=1 -U flashsale -d purchase "$@"
}

db_scalar() {
  psql_exec -Atq -c "$1"
}

require_runtime_target_settings() {
  require_demo_base_url "$DEMO_BASE_URL"
  require_compose_project_name "${COMPOSE_PROJECT_NAME:-}"
  require_empty_docker_override DOCKER_HOST "${DOCKER_HOST:-}"
  require_empty_docker_override DOCKER_CONTEXT "${DOCKER_CONTEXT:-}"
  require_canonical_compose_targets "$DEMO_COMPOSE_FILE" "$DEMO_ENV_FILE" "$REPO_ROOT"
}

require_local_docker_engine() {
  local context endpoint
  context="$(docker context show)" || return 1
  endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}' "$context")" || return 1
  require_local_docker_endpoint "$endpoint"
}

require_healthy_stack() {
  local service container_id health
  local services=(postgres postgres-purchase redis rabbitmq mailpit zipkin backend purchase-service frontend nginx)

  for service in "${services[@]}"; do
    container_id="$(compose ps -q "$service")"
    if [[ -z "$container_id" ]]; then
      printf 'service is not running: %s\n' "$service" >&2
      return 1
    fi
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")"
    if [[ "$health" != 'healthy' ]]; then
      printf 'service is not healthy: %s (%s)\n' "$service" "$health" >&2
      return 1
    fi
  done

  printf 'All 8 Compose services are healthy.\n'
}

require_stack_identity() {
  local service container_id labels project config_file working_dir env_file expected_root
  local services=(postgres redis rabbitmq mailpit zipkin backend frontend nginx)

  expected_root="$REPO_ROOT"
  if command -v cygpath >/dev/null 2>&1; then
    expected_root="$(cygpath -am "$expected_root")"
  fi

  for service in "${services[@]}"; do
    container_id="$(compose ps -q "$service")"
    if [[ -z "$container_id" ]]; then
      printf 'cannot verify Compose identity for missing service: %s\n' "$service" >&2
      return 1
    fi
    labels="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.project.config_files"}}|{{index .Config.Labels "com.docker.compose.project.working_dir"}}|{{index .Config.Labels "com.docker.compose.project.environment_file"}}' "$container_id")"
    IFS='|' read -r project config_file working_dir env_file <<<"$labels"
    require_compose_identity "$project" "$config_file" "$working_dir" "$env_file" "$expected_root" || {
      printf 'Compose identity check failed for service: %s\n' "$service" >&2
      return 1
    }
  done

  printf 'Compose identity verified for project flashsale.\n'
}

require_verified_nginx_api() {
  local container_id bindings
  container_id="$(compose ps -q nginx)"
  [[ -n "$container_id" ]] || return 1
  bindings="$(docker port "$container_id" 8443/tcp)" || return 1
  require_nginx_8443_binding "$bindings" || return 1
  curl --silent --show-error --insecure --fail --output /dev/null "${DEMO_BASE_URL}/actuator/health" || {
    printf 'verified nginx container did not answer the loopback API endpoint\n' >&2
    return 1
  }
  printf 'Loopback API verified against canonical nginx host binding.\n'
}

audit_target_count() {
  local predicate
  predicate="$(demo_audit_predicate "${1:-}")" || return 1
  db_scalar "SELECT count(*) FROM api_audit_logs WHERE ${predicate};"
}

begin_audit_cleanup_barrier() {
  local user_ids="${1:-}" payload
  demo_audit_predicate "$user_ids" >/dev/null || return 1
  payload="{\"userIds\":[${user_ids}],\"traceIds\":[\"${DEMO_USER_TRACE_ID}\",\"${DEMO_ADMIN_TRACE_ID}\"]}"
  compose exec -T backend wget -qO- --header='Content-Type: application/json' \
    --post-data="$payload" http://localhost:8080/internal/demo-data/audit-barrier/begin >/dev/null
}

end_audit_cleanup_barrier() {
  compose exec -T backend wget -qO- --post-data='' \
    http://localhost:8080/internal/demo-data/audit-barrier/end >/dev/null
}

redis_delete_exact_stock() {
  local sale_id="$1" reply
  if [[ ! "$sale_id" =~ ^[0-9]+$ ]]; then
    printf 'refusing invalid demo sale id for Redis cleanup: %s\n' "$sale_id" >&2
    return 1
  fi
  if ! reply="$(compose exec -T redis redis-cli -e DEL "stock:${sale_id}")"; then
    printf 'Redis DEL failed for exact stock key: stock:%s\n' "$sale_id" >&2
    return 1
  fi
  require_redis_del_reply "$reply"
}

redis_reset_exact_stock() {
  local sale_id="$1" reply
  redis_delete_exact_stock "$sale_id" || return 1
  if ! reply="$(compose exec -T redis redis-cli -e SET "stock:${sale_id}" 1000)"; then
    printf 'Redis SET failed for exact stock key: stock:%s\n' "$sale_id" >&2
    return 1
  fi
  require_redis_set_reply "$reply"
}

register_user_if_absent() {
  local email="$1"
  local password="$2"
  local escaped_email existing_count email_json password_json status traceparent

  require_demo_email "$email"
  escaped_email="$(sql_escape_literal "$email")"
  existing_count="$(db_scalar "SELECT count(*) FROM users WHERE email = '${escaped_email}';")"
  if [[ "$existing_count" == '1' ]]; then
    printf 'Demo account already exists: %s\n' "$email"
    return 0
  fi
  if [[ "$existing_count" != '0' ]]; then
    printf 'refusing ambiguous demo account count for %s: %s\n' "$email" "$existing_count" >&2
    return 1
  fi

  email_json="$(json_escape_string "$email")"
  password_json="$(json_escape_string "$password")"
  traceparent="$(demo_traceparent_for_email "$email")"
  status="$({
    printf '{"email":"%s","password":"%s"}' "$email_json" "$password_json"
  } | curl --silent --show-error --insecure \
    --output /dev/null --write-out '%{http_code}' \
    --header 'Content-Type: application/json' \
    --header "traceparent: ${traceparent}" \
    --data-binary @- \
    "${DEMO_BASE_URL}/api/auth/register")"

  existing_count="$(db_scalar "SELECT count(*) FROM users WHERE email = '${escaped_email}';")"
  if [[ "$existing_count" == '1' ]]; then
    if [[ "$status" == '201' ]]; then
      printf 'Demo account ready: %s\n' "$email"
    else
      printf 'Demo account became ready concurrently: %s (HTTP %s)\n' "$email" "$status"
    fi
    return 0
  fi

  printf 'registration failed for %s (HTTP %s, exact count %s)\n' "$email" "$status" "$existing_count" >&2
  return 1
}

seed_demo_data() {
  local activity_absence_predicate advisory_lock_sql ids product_id flash_sale_id

  require_runtime_target_settings
  require_demo_passwords
  require_demo_email "$DEMO_USER_EMAIL"
  require_demo_email "$DEMO_ADMIN_EMAIL"
  require_demo_product_name "$DEMO_PRODUCT_NAME"
  require_demo_prefix "$DEMO_IDENTIFIER_PREFIX"
  require_local_docker_engine
  require_healthy_stack
  require_stack_identity
  require_verified_nginx_api

  register_user_if_absent "$DEMO_USER_EMAIL" "$DEMO_USER_PASSWORD"
  register_user_if_absent "$DEMO_ADMIN_EMAIL" "$DEMO_ADMIN_PASSWORD"

  activity_absence_predicate="$(demo_activity_absence_predicate)"
  advisory_lock_sql="$(demo_seed_advisory_lock_sql)"

  psql_exec \
    -v demo_admin_email="$DEMO_ADMIN_EMAIL" \
    -v demo_product_name="$DEMO_PRODUCT_NAME" <<SQL
BEGIN;

${advisory_lock_sql}

UPDATE users
SET role = 'ADMIN', updated_at = now()
WHERE email = :'demo_admin_email';

INSERT INTO products (name, description)
SELECT :'demo_product_name', 'Idempotent local portfolio demo fixture'
WHERE NOT EXISTS (
    SELECT 1 FROM products WHERE name = :'demo_product_name'
);

UPDATE products
SET description = 'Idempotent local portfolio demo fixture', updated_at = now()
WHERE id = (
    SELECT id FROM products WHERE name = :'demo_product_name' ORDER BY id LIMIT 1
);

INSERT INTO flash_sales (
    product_id, sale_price, starts_at, ends_at, purchase_limit_per_user, status
)
SELECT id, 99.00, now() - interval '1 day', now() + interval '30 days', 2, 'ACTIVE'
FROM products
WHERE name = :'demo_product_name'
  AND ${activity_absence_predicate}
ORDER BY id
LIMIT 1
ON CONFLICT DO NOTHING;

UPDATE flash_sales
SET sale_price = 99.00,
    starts_at = now() - interval '1 day',
    ends_at = now() + interval '30 days',
    purchase_limit_per_user = 2,
    status = 'ACTIVE',
    updated_at = now()
WHERE id = (
    SELECT fs.id
    FROM flash_sales fs
    JOIN products p ON p.id = fs.product_id
    WHERE p.name = :'demo_product_name'
    ORDER BY fs.id
    LIMIT 1
);

INSERT INTO inventory (
    flash_sale_id, total_quantity, available_quantity, reserved_quantity, sold_quantity, version
)
SELECT fs.id, 1000, 1000, 0, 0, 0
FROM flash_sales fs
JOIN products p ON p.id = fs.product_id
WHERE p.name = :'demo_product_name'
ORDER BY fs.id
LIMIT 1
ON CONFLICT (flash_sale_id) DO UPDATE
SET total_quantity = EXCLUDED.total_quantity,
    available_quantity = EXCLUDED.available_quantity,
    reserved_quantity = EXCLUDED.reserved_quantity,
    sold_quantity = EXCLUDED.sold_quantity,
    version = inventory.version + 1;

COMMIT;
SQL

  ids="$(db_scalar "SELECT p.id || '|' || fs.id FROM products p JOIN flash_sales fs ON fs.product_id = p.id WHERE p.name = '$(sql_escape_literal "$DEMO_PRODUCT_NAME")' ORDER BY p.id, fs.id LIMIT 1;")"
  IFS='|' read -r product_id flash_sale_id <<<"$ids"
  if [[ -z "$product_id" || -z "$flash_sale_id" ]]; then
    printf 'demo product or flash sale was not created\n' >&2
    return 1
  fi

  redis_reset_exact_stock "$flash_sale_id" || return 1

  printf 'Demo data is ready (product=%s, flashSale=%s).\n' "$product_id" "$flash_sale_id"
  printf 'Storefront: %s/\n' "$DEMO_BASE_URL"
  printf 'Demo sale API: %s/api/flash-sales/%s\n' "$DEMO_BASE_URL" "$flash_sale_id"
  printf 'Swagger UI: %s/swagger-ui/index.html\n' "$DEMO_BASE_URL"
  printf 'Zipkin: http://localhost:9411/\n'
}

# purchase-service 資料庫裡的示範資料。它的 purchase_requests 只認得使用者 id 與活動 id，
# 而那兩張表都在 platform —— 所以 id 清單必須由呼叫端先在 platform 查好再傳進來。
# 這就是跨服務查詢的真實樣子：沒有 JOIN，只有「先查一邊、把鍵帶過去、再查另一邊」。
purchase_request_predicate() {
  local user_ids="$1" sale_ids="$2" predicate
  predicate="$(demo_prefix_predicate idempotency_key)"
  if [[ -n "$user_ids" ]]; then
    predicate="${predicate} OR user_id IN (${user_ids})"
  fi
  if [[ -n "$sale_ids" ]]; then
    predicate="${predicate} OR flash_sale_id IN (${sale_ids})"
  fi
  printf '%s' "$predicate"
}

list_purchase_cleanup_counts() {
  local predicate
  predicate="$(purchase_request_predicate "$1" "$2")"
  psql_purchase_exec -At <<SQL
WITH demo_requests AS (
    SELECT id FROM purchase_requests WHERE ${predicate}
)
SELECT 'purchase.purchase_requests=' || (SELECT count(*) FROM demo_requests)
UNION ALL SELECT 'purchase.outbox_events=' || (SELECT count(*) FROM outbox_events
    WHERE $(demo_prefix_predicate aggregate_id)
       OR (aggregate_type = 'PurchaseRequest' AND aggregate_id IN (SELECT id::text FROM demo_requests)));
SQL
}

delete_purchase_demo_data() {
  local predicate
  predicate="$(purchase_request_predicate "$1" "$2")"
  psql_purchase_exec <<SQL
BEGIN;

CREATE TEMP TABLE demo_request_ids ON COMMIT DROP AS
SELECT id FROM purchase_requests WHERE ${predicate};

DELETE FROM outbox_events
WHERE $(demo_prefix_predicate aggregate_id)
   OR (aggregate_type = 'PurchaseRequest' AND aggregate_id IN (SELECT id::text FROM demo_request_ids));
DELETE FROM purchase_requests WHERE id IN (SELECT id FROM demo_request_ids);

COMMIT;
SQL
}

list_cleanup_counts() {
  local order_predicate idempotency_predicate message_predicate aggregate_predicate
  order_predicate="$(demo_prefix_predicate order_no)"
  idempotency_predicate="$(demo_prefix_predicate idempotency_key)"
  message_predicate="$(demo_prefix_predicate message_id)"
  aggregate_predicate="$(demo_prefix_predicate aggregate_id)"

  psql_exec -At \
    -v demo_user_email="$DEMO_USER_EMAIL" \
    -v demo_admin_email="$DEMO_ADMIN_EMAIL" \
    -v demo_product_name="$DEMO_PRODUCT_NAME" \
    -v demo_user_trace_id="$DEMO_USER_TRACE_ID" \
    -v demo_admin_trace_id="$DEMO_ADMIN_TRACE_ID" <<SQL
WITH demo_users AS (
    SELECT id FROM users WHERE email IN (:'demo_user_email', :'demo_admin_email')
), demo_products AS (
    SELECT id FROM products WHERE name = :'demo_product_name'
), demo_sales AS (
    SELECT id FROM flash_sales WHERE product_id IN (SELECT id FROM demo_products)
), demo_orders AS (
    -- 拆庫前這裡還要繞經 purchase_requests 才找得到訂單；現在 orders 自己有 flash_sale_id。
    SELECT id FROM orders
    WHERE user_id IN (SELECT id FROM demo_users)
       OR ${order_predicate}
       OR flash_sale_id IN (SELECT id FROM demo_sales)
       OR id IN (SELECT order_id FROM order_items WHERE product_id IN (SELECT id FROM demo_products))
), demo_outbox AS (
    SELECT id FROM outbox_events
    WHERE ${aggregate_predicate}
       OR (aggregate_type = 'PurchaseRequest' AND aggregate_id IN (
              SELECT purchase_request_id::text FROM orders WHERE id IN (SELECT id FROM demo_orders)))
       OR (aggregate_type = 'Order' AND aggregate_id IN (SELECT id::text FROM demo_orders))
)
SELECT 'users=' || (SELECT count(*) FROM demo_users)
UNION ALL SELECT 'products=' || (SELECT count(*) FROM demo_products)
UNION ALL SELECT 'flash_sales=' || (SELECT count(*) FROM demo_sales)
UNION ALL SELECT 'inventory=' || (SELECT count(*) FROM inventory WHERE flash_sale_id IN (SELECT id FROM demo_sales))
UNION ALL SELECT 'orders=' || (SELECT count(*) FROM demo_orders)
UNION ALL SELECT 'order_items=' || (SELECT count(*) FROM order_items WHERE order_id IN (SELECT id FROM demo_orders) OR product_id IN (SELECT id FROM demo_products))
UNION ALL SELECT 'payment_records=' || (SELECT count(*) FROM payment_records WHERE order_id IN (SELECT id FROM demo_orders))
UNION ALL SELECT 'order_status_history=' || (SELECT count(*) FROM order_status_history WHERE order_id IN (SELECT id FROM demo_orders))
UNION ALL SELECT 'refresh_tokens=' || (SELECT count(*) FROM refresh_tokens WHERE user_id IN (SELECT id FROM demo_users))
UNION ALL SELECT 'notification_deliveries=' || (SELECT count(*) FROM notification_deliveries WHERE user_id IN (SELECT id FROM demo_users))
UNION ALL SELECT 'outbox_events=' || (SELECT count(*) FROM demo_outbox)
UNION ALL SELECT 'consumed_messages=' || (SELECT count(*) FROM consumed_messages WHERE ${message_predicate} OR message_id IN (SELECT id::text FROM demo_outbox))
UNION ALL SELECT 'api_audit_logs=' || (SELECT count(*) FROM api_audit_logs WHERE user_id IN (SELECT id FROM demo_users) OR trace_id IN (:'demo_user_trace_id', :'demo_admin_trace_id'));
SQL
}

cleanup_demo_data() {
  local database_name sale_ids sale_id demo_user_ids
  local order_predicate idempotency_predicate message_predicate aggregate_predicate

  require_runtime_target_settings
  require_demo_email "$DEMO_USER_EMAIL"
  require_demo_email "$DEMO_ADMIN_EMAIL"
  require_demo_product_name "$DEMO_PRODUCT_NAME"
  require_demo_prefix "$DEMO_IDENTIFIER_PREFIX"
  require_local_docker_engine
  require_healthy_stack
  require_stack_identity
  require_verified_nginx_api

  database_name="$(db_scalar 'SELECT current_database();')"
  require_flashsale_database "$database_name"

  order_predicate="$(demo_prefix_predicate order_no)"
  idempotency_predicate="$(demo_prefix_predicate idempotency_key)"
  message_predicate="$(demo_prefix_predicate message_id)"
  aggregate_predicate="$(demo_prefix_predicate aggregate_id)"

  sale_ids="$(db_scalar "SELECT string_agg(fs.id::text, ',' ORDER BY fs.id) FROM flash_sales fs JOIN products p ON p.id = fs.product_id WHERE p.name = '$(sql_escape_literal "$DEMO_PRODUCT_NAME")';")"
  demo_user_ids="$(db_scalar "SELECT string_agg(id::text, ',' ORDER BY id) FROM users WHERE email IN ('$(sql_escape_literal "$DEMO_USER_EMAIL")', '$(sql_escape_literal "$DEMO_ADMIN_EMAIL")');")"

  printf 'Cleanup targets in database %s:\n' "$database_name"
  list_cleanup_counts
  list_purchase_cleanup_counts "$demo_user_ids" "$sale_ids"

  if [[ -n "$sale_ids" ]]; then
    IFS=',' read -ra demo_sale_ids <<<"$sale_ids"
    for sale_id in "${demo_sale_ids[@]}"; do
      redis_delete_exact_stock "$sale_id" || return 1
    done
  fi

  demo_audit_predicate "$demo_user_ids" >/dev/null || return 1

  # 先清 purchase 再清 platform：反過來的話 platform 的 users/flash_sales 先沒了，
  # 上面那兩個 id 清單就再也查不出來，purchase 那邊的列會變成永遠清不掉的孤兒。
  # 位置在稽核柵欄之前，因為柵欄一旦開啟就必須走到 end_audit_cleanup_barrier —— 
  # 在柵欄裡提早 return 會把它留在開啟狀態。
  delete_purchase_demo_data "$demo_user_ids" "$sale_ids" || return 1
  begin_audit_cleanup_barrier "$demo_user_ids" || return 1

  local cleanup_status=0 remaining_audits
  psql_exec \
    -v demo_user_email="$DEMO_USER_EMAIL" \
    -v demo_admin_email="$DEMO_ADMIN_EMAIL" \
    -v demo_product_name="$DEMO_PRODUCT_NAME" \
    -v demo_user_trace_id="$DEMO_USER_TRACE_ID" \
    -v demo_admin_trace_id="$DEMO_ADMIN_TRACE_ID" <<SQL || cleanup_status=$?
BEGIN;

CREATE TEMP TABLE demo_user_ids ON COMMIT DROP AS
SELECT id FROM users WHERE email IN (:'demo_user_email', :'demo_admin_email');

CREATE TEMP TABLE demo_product_ids ON COMMIT DROP AS
SELECT id FROM products WHERE name = :'demo_product_name';

CREATE TEMP TABLE demo_sale_ids ON COMMIT DROP AS
SELECT id FROM flash_sales WHERE product_id IN (SELECT id FROM demo_product_ids);

CREATE TEMP TABLE demo_order_ids ON COMMIT DROP AS
SELECT id FROM orders
WHERE user_id IN (SELECT id FROM demo_user_ids)
   OR ${order_predicate}
   OR flash_sale_id IN (SELECT id FROM demo_sale_ids)
   OR id IN (SELECT order_id FROM order_items WHERE product_id IN (SELECT id FROM demo_product_ids));

CREATE TEMP TABLE demo_outbox_ids ON COMMIT DROP AS
SELECT id FROM outbox_events
WHERE ${aggregate_predicate}
   OR (aggregate_type = 'PurchaseRequest' AND aggregate_id IN (
          SELECT purchase_request_id::text FROM orders WHERE id IN (SELECT id FROM demo_order_ids)))
   OR (aggregate_type = 'Order' AND aggregate_id IN (SELECT id::text FROM demo_order_ids));

DELETE FROM consumed_messages
WHERE ${message_predicate}
   OR message_id IN (SELECT id::text FROM demo_outbox_ids);
DELETE FROM outbox_events WHERE id IN (SELECT id FROM demo_outbox_ids);
DELETE FROM api_audit_logs
WHERE user_id IN (SELECT id FROM demo_user_ids)
   OR trace_id IN (:'demo_user_trace_id', :'demo_admin_trace_id');
DELETE FROM payment_records WHERE order_id IN (SELECT id FROM demo_order_ids);
DELETE FROM order_status_history WHERE order_id IN (SELECT id FROM demo_order_ids);
DELETE FROM order_items
WHERE order_id IN (SELECT id FROM demo_order_ids)
   OR product_id IN (SELECT id FROM demo_product_ids);
DELETE FROM orders WHERE id IN (SELECT id FROM demo_order_ids);
DELETE FROM inventory WHERE flash_sale_id IN (SELECT id FROM demo_sale_ids);
DELETE FROM flash_sales WHERE id IN (SELECT id FROM demo_sale_ids);
DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM demo_user_ids);
DELETE FROM notification_deliveries WHERE user_id IN (SELECT id FROM demo_user_ids);
DELETE FROM products WHERE id IN (SELECT id FROM demo_product_ids);
DELETE FROM users WHERE id IN (SELECT id FROM demo_user_ids);

COMMIT;
SQL
  if (( cleanup_status == 0 )); then
    remaining_audits="$(audit_target_count "$demo_user_ids")" || cleanup_status=$?
    if [[ "$remaining_audits" != '0' ]]; then
      printf 'demo audits remain after exact cleanup: %s\n' "$remaining_audits" >&2
      cleanup_status=1
    fi
  fi
  end_audit_cleanup_barrier || cleanup_status=$?
  (( cleanup_status == 0 )) || return "$cleanup_status"

  printf 'Demo cleanup complete. Running it again is safe.\n'
}

main() {
  local command="${1:-}"
  require_supported_command "$command"
  if (( $# != 1 )); then
    printf 'usage: %s seed|cleanup\n' "$0" >&2
    return 1
  fi

  case "$command" in
    seed) seed_demo_data ;;
    cleanup) cleanup_demo_data ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
