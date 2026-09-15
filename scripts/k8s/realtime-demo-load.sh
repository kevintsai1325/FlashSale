#!/usr/bin/env bash
# P6 的示範流量：讓即時大屏有東西可看，同時產生「Flink 的 GMV 與 order_db 的 SUM 一致」
# 這條驗收條件所需要的資料。
#
# 刻意做成兩場活動、不同單價：Top N 才有排名可言，GMV 才不是單價乘以筆數。
set -euo pipefail

BASE_URL="${BASE_URL:-https://localhost:8443}"
CONTEXT="${KUBE_CONTEXT:-rancher-desktop}"
NS="${NAMESPACE:-flashsale}"
BUYERS="${BUYERS:-24}"
K="kubectl --context $CONTEXT -n $NS"

json_field() { python -c "import sys,json
try:
    print(json.load(sys.stdin)['$1'])
except Exception:
    pass"; }

# Nginx 對 /api/auth/ 限流 5r/s、burst 10，所以退避重試（同 chaos-order-service.sh）。
register_and_login() {
  local email="$1" body attempt token
  body="{\"email\":\"$email\",\"password\":\"secret123\"}"
  for attempt in 1 2 3 4 5 6 7 8; do
    curl -sk -o /dev/null -X POST "$BASE_URL/api/auth/register" -H 'Content-Type: application/json' -d "$body" || true
    token="$(curl -sk -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' -d "$body" | json_field accessToken)"
    if [[ -n "$token" ]]; then printf '%s' "$token"; return 0; fi
    sleep "$attempt"
  done
  printf 'could not obtain a token for %s\n' "$email" >&2
  return 1
}

run_id="p6-$(date +%s)"
register_and_login "$run_id-admin@example.com" >/dev/null
$K exec postgres-0 -- psql -U flashsale -d flashsale -q -c \
  "update users set role='ADMIN' where email='$run_id-admin@example.com'" >/dev/null
admin_token="$(register_and_login "$run_id-admin@example.com")"

starts="$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"
ends="$(python -c "import datetime;print((datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=2)).strftime('%Y-%m-%dT%H:%M:%SZ'))")"

# 商品名稱刻意用 ASCII：這台開發機的 Git Bash 會把命令列參數裡的中文轉成系統代碼頁
# （Big5），送到 API 就是無效的 UTF-8，症狀是一個沒有訊息的 400。
# 中文的顯示名稱由後台 UI 輸入，不是這支腳本的職責。
sales=()
prices=(10.00 25.00)
for index in 0 1; do
  product_id="$(curl -sk -X POST "$BASE_URL/api/admin/products" -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' -d "{\"name\":\"$run_id item $index\",\"description\":\"realtime demo\"}" | json_field id)"
  sale_id="$(curl -sk -X POST "$BASE_URL/api/admin/flash-sales" -H "Authorization: Bearer $admin_token" \
    -H 'Content-Type: application/json' \
    -d "{\"productId\":$product_id,\"salePrice\":${prices[$index]},\"startsAt\":\"$starts\",\"endsAt\":\"$ends\",\"purchaseLimitPerUser\":1,\"totalQuantity\":$BUYERS}" \
    | json_field id)"
  sales+=("$sale_id")
  printf 'flash sale %s at %s\n' "$sale_id" "${prices[$index]}"
done

printf 'preparing %s buyer tokens\n' "$BUYERS"
tokens=()
for i in $(seq 1 "$BUYERS"); do
  tokens+=("$(register_and_login "$run_id-buyer-$i@example.com")")
done

# 驗收的時間錨點。在第一筆購買之前取，並且刻意讓它比前面的準備工作晚 ——
# verify-realtime-gmv.sh 用它把「這一輪的訂單」與「更早的殘留」分開。
since_ms="$(python -c "import time;print(int(time.time()*1000))")"
printf 'since=%s\n' "$since_ms"

# **一秒一位買家，而不是一次全部打出去。**
#
# 這不是為了溫柔，是事件時間視窗的必然：watermark 是「已見的最大事件時間減去允許的亂序」，
# 所以最後幾秒的視窗要等到更晚的事件到達才會定案。把 24 筆擠在兩秒內送完，
# 它們全部落在 watermark 還沒越過的區間，大屏會一格都不動 —— 看起來像 Flink 壞了，
# 其實是「串流沒有結束，只是暫停了」。
#
# 真實的秒殺流量是連續的，所以這個形狀也比較像真的。
printf 'firing purchases\n'
for i in $(seq 1 "$BUYERS"); do
  # 第一場活動每個人都買，第二場只有偶數號買 —— 讓 Top N 有明確的名次差距。
  curl -sk -o /dev/null -X POST "$BASE_URL/api/flash-sales/${sales[0]}/purchase-requests" \
    -H "Authorization: Bearer ${tokens[$((i-1))]}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $run_id-a-$i" -d '{"quantity":1}' &
  if (( i % 2 == 0 )); then
    curl -sk -o /dev/null -X POST "$BASE_URL/api/flash-sales/${sales[1]}/purchase-requests" \
      -H "Authorization: Bearer ${tokens[$((i-1))]}" -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $run_id-b-$i" -d '{"quantity":1}' &
  fi
  sleep 1
done
wait

printf 'done. flash sales: %s\n' "${sales[*]}"
printf 'verify with: bash scripts/k8s/verify-realtime-gmv.sh %s\n' "$since_ms"
