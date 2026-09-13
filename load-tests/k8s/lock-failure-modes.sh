#!/usr/bin/env bash
# 分散式鎖的故障模式實測。對應 Week 8 P2 Task 7。
#
# 時間量測的重要約束：**所有計時都在 Windows 這一側完成。**
# 2026-09-13 實測，WSL2 VM 的牆鐘約每 30 秒被校正一次、每次往回跳約 1.5 秒
# （Windows 原生 30 秒內 0 次回跳）。跨越兩個時鐘去相減，誤差會大到吃掉整個量測。
# 因此本腳本從 Windows 輪詢 Redis 的鎖鍵狀態，起訖時間都由 Windows 的時鐘決定。
#
# 用法：
#   ./lock-failure-modes.sh <情境>
# 情境：
#   holder-crash   強制刪除持鎖的 Pod，量其他副本多久才取得鎖
#   redis-down     把 Redis 縮到 0，觀察排程是否全部停擺、恢復後能否自動復原
#   lock-state     只印出目前的鎖狀態（除錯用）

set -euo pipefail
export MSYS_NO_PATHCONV=1

NAMESPACE="${NAMESPACE:-flashsale}"
CONTEXT="${CONTEXT:-rancher-desktop}"
KUBECTL="${KUBECTL:-kubectl}"
LOCK_KEY="${LOCK_KEY:-scheduler:expireOverduePayments}"

k() { "$KUBECTL" --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

redis() { k exec statefulset/redis -- redis-cli "$@" 2>/dev/null | tr -d '\r'; }

lock_holder_pod() {
  # Redisson 的 RLock 是 hash，field 為 "<UUID>:<threadId>"。UUID 是 RedissonClient 的
  # 實例識別，對應到某一個 backend Pod，但 Redis 這側看不出是哪個 Pod。
  # 因此改以「鎖存在與否」為觀察對象，而不是試圖反查持有者。
  redis exists "$LOCK_KEY"
}

now_ms() { date +%s%3N; }

case "${1:-}" in
  lock-state)
    echo "lock key   : $LOCK_KEY"
    echo "exists     : $(redis exists "$LOCK_KEY")"
    echo "ttl (ms)   : $(redis pttl "$LOCK_KEY")"
    echo "type       : $(redis type "$LOCK_KEY")"
    echo "fields     : $(redis hgetall "$LOCK_KEY" | tr '\n' ' ')"
    ;;

  holder-crash)
    echo "=== 等待鎖被某個副本取得 ==="
    deadline=$(( $(now_ms) + 120000 ))
    while [ "$(lock_holder_pod)" != "1" ]; do
      [ "$(now_ms)" -gt "$deadline" ] && { echo "120 秒內沒有觀察到鎖被取得；排程可能沒在跑"; exit 1; }
      sleep 0.2
    done
    ttl_before="$(redis pttl "$LOCK_KEY")"
    echo "鎖已被持有，剩餘租約 ${ttl_before} ms"

    # 強制刪掉所有 backend Pod 中的一個。無法從 Redis 得知是哪一個持有，
    # 所以刪第一個；若刪到非持有者，鎖會繼續存在，腳本會如實記錄「鎖未釋放」。
    victim="$(k get pods -l app=backend -o jsonpath='{.items[0].metadata.name}')"
    echo "=== 強制刪除 $victim ==="
    t0="$(now_ms)"
    k delete pod "$victim" --force --grace-period=0 >/dev/null 2>&1

    echo "=== 等待鎖釋放（由 Windows 計時）==="
    deadline=$(( t0 + 180000 ))
    while [ "$(lock_holder_pod)" = "1" ]; do
      if [ "$(now_ms)" -gt "$deadline" ]; then
        echo "結果：180 秒內鎖未釋放。可能刪到的不是持有者，或租約比預期長。"
        exit 0
      fi
      sleep 0.2
    done
    t1="$(now_ms)"
    echo "結果：鎖在強制刪除後 $((t1 - t0)) ms 釋放（刪除前剩餘租約 ${ttl_before} ms）"
    echo "預期：約等於剩餘租約 —— 租約到期由 Redis 自動釋放，不依賴持有者主動解鎖。"
    ;;

  redis-down)
    echo "=== Redis 停機前的鎖指標 ==="
    before="$(k exec statefulset/redis -- redis-cli info keyspace 2>/dev/null | tr -d '\r' | head -3)"
    echo "$before"
    echo "=== 將 Redis 縮到 0 ==="
    k scale statefulset/redis --replicas=0 >/dev/null
    k wait --for=delete pod/redis-0 --timeout=120s >/dev/null 2>&1 || true
    echo "Redis 已停止。觀察 90 秒 backend 的行為 ——"
    echo "  預期：排程全部停擺（取不到鎖），但 HTTP 服務本身不應該掛掉。"
    t_end=$(( $(now_ms) + 90000 ))
    while [ "$(now_ms)" -lt "$t_end" ]; do sleep 5; done
    echo "--- backend 的 readiness ---"
    k get pods -l app=backend -o custom-columns=NAME:.metadata.name,READY:.status.containerStatuses[0].ready --no-headers
    echo "--- 排程相關的錯誤日誌（最近 90 秒）---"
    k logs -l app=backend --since=90s --prefix 2>/dev/null | grep -icE "redis|lock|connection" || echo "0"
    echo "=== 恢復 Redis ==="
    k scale statefulset/redis --replicas=1 >/dev/null
    k rollout status statefulset/redis --timeout=180s
    echo "已恢復。再觀察 90 秒，確認排程自動復原 ——"
    t_end=$(( $(now_ms) + 90000 ))
    while [ "$(now_ms)" -lt "$t_end" ]; do sleep 5; done
    echo "鎖是否重新出現: $(lock_holder_pod)"
    ;;

  *)
    echo "用法: $0 {holder-crash|redis-down|lock-state}" >&2
    exit 2
    ;;
esac
