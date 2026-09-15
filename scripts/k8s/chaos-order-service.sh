#!/usr/bin/env bash
# P5 的混沌測試：搶購進行中強制刪掉 order-service 的 Pod，驗證恢復後資料最終一致。
#
# 這個測試證明的是**補償與重試真的有用**，而不是「系統沒壞」：
# 建單是 RabbitMQ 的消費端，Pod 被砍掉時未 ack 的訊息會回到佇列由別的副本接手；
# 搶購請求的終態則由 purchase-service 自己的 consumer 寫入。兩段都是至少一次。
#
# 不變量（全部必須成立）：
#   1. 沒有搶購請求停在 PENDING
#   2. 訂單數 == SUCCEEDED 的搶購請求數（不漏單、不重複下單）
#   3. available + sold == total（庫存不漏不溢）
#   4. 兩個 outbox 都清空，三條 DLQ 都是空的
set -euo pipefail

BASE_URL="${BASE_URL:-https://localhost:8443}"
CONTEXT="${KUBE_CONTEXT:-rancher-desktop}"
NS="${NAMESPACE:-flashsale}"
BUYERS="${BUYERS:-20}"
STOCK="${STOCK:-30}"
K="kubectl --context $CONTEXT -n $NS"

psql_order() { $K exec postgres-order-0 -- psql -U flashsale -d orders -t -A -c "$1"; }
psql_purchase() { $K exec postgres-purchase-0 -- psql -U flashsale -d purchase -t -A -c "$1"; }

json_field() { python -c "import sys,json
try:
    print(json.load(sys.stdin)['$1'])
except Exception:
    pass"; }

# Nginx 對 /api/auth/ 套了 5r/s、burst 10 的限流（那是刻意的，見 nginx.conf）。
# 這個測試要一次準備二十個買家，一定會撞到 —— 所以退避重試。
# 被限流擋下不是系統故障，把它當成故障會讓這個測試變成在測 Nginx 的設定。
register_and_login() {
  local email="$1" body attempt token
  body="{\"email\":\"$email\",\"password\":\"secret123\"}"
  for attempt in 1 2 3 4 5 6 7 8; do
    curl -sk -o /dev/null -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' -d "$body" || true
    token="$(curl -sk -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d "$body" | json_field accessToken)"
    if [[ -n "$token" ]]; then
      printf '%s' "$token"
      return 0
    fi
    sleep "$attempt"
  done
  printf 'could not obtain a token for %s\n' "$email" >&2
  return 1
}

run_id="chaos-$(date +%s)"
printf 'run: %s\n' "$run_id"

register_and_login "$run_id-admin@example.com" >/dev/null
$K exec postgres-0 -- psql -U flashsale -d flashsale -q -c \
  "update users set role='ADMIN' where email='$run_id-admin@example.com'" >/dev/null
admin_token="$(register_and_login "$run_id-admin@example.com")"

product_id="$(curl -sk -X POST "$BASE_URL/api/admin/products" -H "Authorization: Bearer $admin_token" \
  -H 'Content-Type: application/json' -d "{\"name\":\"$run_id item\",\"description\":\"chaos\"}" | json_field id)"

starts="$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"
ends="$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"
sale_id="$(curl -sk -X POST "$BASE_URL/api/admin/flash-sales" -H "Authorization: Bearer $admin_token" \
  -H 'Content-Type: application/json' \
  -d "{\"productId\":$product_id,\"salePrice\":9.99,\"startsAt\":\"$starts\",\"endsAt\":\"$ends\",\"purchaseLimitPerUser\":1,\"totalQuantity\":$STOCK}" \
  | json_field id)"
printf 'flash sale %s seeded with %s units\n' "$sale_id" "$STOCK"

printf 'preparing %s buyer tokens\n' "$BUYERS"
tokens=()
for i in $(seq 1 "$BUYERS"); do
  tokens+=("$(register_and_login "$run_id-buyer-$i@example.com")")
done

printf 'purchasing while order-service is being killed\n'
for i in $(seq 1 "$BUYERS"); do
  curl -sk -o /dev/null -X POST "$BASE_URL/api/flash-sales/$sale_id/purchase-requests" \
    -H "Authorization: Bearer ${tokens[$((i-1))]}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $run_id-$i" -d '{"quantity":1}' &
  # 在中段強制刪除，確保有訊息正在被消費。--force 是刻意的：
  # 優雅關閉會讓消費端把手上的訊息處理完，那就不是「當掉」而是「下線」。
  if [[ "$i" == "$((BUYERS / 2))" ]]; then
    $K delete pod -l app=order-service --force --grace-period=0 >/dev/null 2>&1 || true
    printf '  order-service pods force-deleted after %s requests\n' "$i"
  fi
done
wait

printf 'waiting for order-service to come back\n'
$K rollout status deployment/order-service --timeout=300s >/dev/null

printf 'waiting for the system to settle\n'
for _ in $(seq 1 60); do
  pending="$(psql_purchase "select count(*) from purchase_requests where status='PENDING'")"
  unpublished_purchase="$(psql_purchase "select count(*) from outbox_events where published_at is null")"
  unpublished_order="$(psql_order "select count(*) from outbox_events where published_at is null")"
  if [[ "$pending" == '0' && "$unpublished_purchase" == '0' && "$unpublished_order" == '0' ]]; then
    break
  fi
  sleep 2
done

succeeded="$(psql_purchase "select count(*) from purchase_requests where status='SUCCEEDED' and flash_sale_id=$sale_id")"
sold_out="$(psql_purchase "select count(*) from purchase_requests where status='SOLD_OUT' and flash_sale_id=$sale_id")"
failed="$(psql_purchase "select count(*) from purchase_requests where status='FAILED' and flash_sale_id=$sale_id")"
pending="$(psql_purchase "select count(*) from purchase_requests where status='PENDING' and flash_sale_id=$sale_id")"
orders="$(psql_order "select count(*) from orders where flash_sale_id=$sale_id")"
read -r total available sold <<<"$(psql_order "select total_quantity || ' ' || available_quantity || ' ' || sold_quantity from inventory where flash_sale_id=$sale_id")"
dlq="$($K exec rabbitmq-0 -- rabbitmqctl list_queues --quiet name messages 2>/dev/null | awk '/dlq/ {sum += $2} END {print sum+0}')"

printf '\n--- results ---\n'
printf 'purchase requests: SUCCEEDED=%s SOLD_OUT=%s FAILED=%s PENDING=%s\n' "$succeeded" "$sold_out" "$failed" "$pending"
printf 'orders=%s  inventory total=%s available=%s sold=%s  dlq_depth=%s\n' "$orders" "$total" "$available" "$sold" "$dlq"

status=0
check() { if [[ "$2" == "$3" ]]; then printf 'ok   - %s\n' "$1"; else printf 'FAIL - %s (got %s, want %s)\n' "$1" "$2" "$3"; status=1; fi; }
check 'no purchase request left PENDING' "$pending" '0'
check 'orders match the succeeded purchase requests' "$orders" "$succeeded"
check 'inventory adds up (available + sold == total)' "$((available + sold))" "$total"
check 'sold quantity matches the order count' "$sold" "$orders"
check 'every DLQ is empty' "$dlq" '0'
exit "$status"
