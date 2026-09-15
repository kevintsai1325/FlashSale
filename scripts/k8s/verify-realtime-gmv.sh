#!/usr/bin/env bash
# P6 的驗收：Flink 算出來的 GMV 與 order_db 的 SUM 必須一致。
#
# 這條驗收在問的是「**串流的聚合與批次的真相會不會對得起來**」。兩者的來源不同 ——
# 一個是事件流、一個是資料庫的 row —— 對得起來才代表事件流沒有漏掉、也沒有重複算。
#
# 用法：bash scripts/k8s/verify-realtime-gmv.sh <since-epoch-millis>
# 那個時間戳由 realtime-demo-load.sh 印出來。
#
# **比較的是「已經關閉的視窗」那一段時間，不是「到現在為止」。**
# 事件時間的視窗要等 watermark 越過它才定案，而 watermark 是「已見的最大事件時間減去
# 允許的亂序」—— 所以最後幾秒的訂單一定還沒被算進任何視窗。
# 拿「到現在為止」去比，量到的是這個必然的延遲，不是正確性。
# 上界因此取 Flink 這一側最大的 windowEnd：那是串流明確宣告「這之前我算完了」的時刻。
set -euo pipefail

if [[ $# -lt 1 ]]; then
  printf 'usage: %s <since-epoch-millis>\n' "$0" >&2
  exit 2
fi
SINCE_MS="$1"
CONTEXT="${KUBE_CONTEXT:-rancher-desktop}"
NS="${NAMESPACE:-flashsale}"
TIMEOUT_MS="${TIMEOUT_MS:-15000}"
K="kubectl --context $CONTEXT -n $NS"

# 同一個 windowEnd 可能因為遲到事件而出現多次 —— 後到的是重算後的完整值，
# 所以用「以 windowEnd 為鍵覆蓋」而不是累加，與大屏的處理一致。
flink_totals="$(MSYS_NO_PATHCONV=1 $K exec kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic flashsale.realtime-metrics --from-beginning --timeout-ms "$TIMEOUT_MS" 2>/dev/null \
  | SINCE_MS="$SINCE_MS" python -c "
import os, sys, json
from decimal import Decimal
since = int(os.environ['SINCE_MS'])
windows = {}
for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        event = json.loads(line)
    except ValueError:
        continue
    if event.get('type') != 'gmv' or event['windowEnd'] <= since:
        continue
    windows[event['windowEnd']] = (Decimal(str(event['amount'])), event['orderCount'])
if not windows:
    print('0 0 0')
else:
    amount = sum((value[0] for value in windows.values()), Decimal(0))
    count = sum(value[1] for value in windows.values())
    print(f'{amount} {count} {max(windows)}')
")"
read -r flink_amount flink_count until_ms <<<"$flink_totals"

if [[ "$until_ms" == '0' ]]; then
  printf 'no closed GMV window after %s — 串流還沒有輸出任何視窗\n' "$SINCE_MS" >&2
  exit 1
fi

read -r db_amount db_count <<<"$($K exec postgres-order-0 -- psql -U flashsale -d orders -t -A -c \
  "select coalesce(sum(total_amount),0) || ' ' || count(*) from orders
   where created_at >= to_timestamp($SINCE_MS / 1000.0)
     and created_at < to_timestamp($until_ms / 1000.0)")"

printf 'window  : [%s, %s)\n' "$SINCE_MS" "$until_ms"
printf 'order_db: amount=%s count=%s\n' "$db_amount" "$db_count"
printf 'flink   : amount=%s count=%s\n' "$flink_amount" "$flink_count"

status=0
compare() {
  if SEEN="$2" WANT="$3" python -c "
import os, sys
from decimal import Decimal
sys.exit(0 if Decimal(os.environ['SEEN']) == Decimal(os.environ['WANT']) else 1)
"; then
    printf 'ok   - %s\n' "$1"
  else
    printf 'FAIL - %s (flink %s vs order_db %s)\n' "$1" "$2" "$3"
    status=1
  fi
}
compare 'GMV matches the authoritative order_db sum' "$flink_amount" "$db_amount"
compare 'order count matches' "$flink_count" "$db_count"
exit "$status"
