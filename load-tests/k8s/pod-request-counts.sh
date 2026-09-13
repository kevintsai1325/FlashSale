#!/usr/bin/env bash
# 印出每個 backend Pod 目前累計處理的 HTTP 請求數，用來觀察 kube-proxy 的負載分配。
#
# 為什麼要這樣繞：Spring Security 把 /actuator/metrics/** 限制為 ROLE_ADMIN，而 Pod IP
# (10.42.x.x) 只在叢集網路內可達。因此需要一個常駐在叢集裡的探測 Pod，帶著管理員 token
# 直接打每個 Pod 的 IP —— 打 Service 會被負載平衡，量不到「誰處理了多少」。
#
# 指標名稱是 Micrometer 的點號形式 http.server.requests。底線形式 http_server_requests
# 只存在於 Prometheus 匯出端點，對 /actuator/metrics 會回 404。
#
# 用法：
#   ADMIN_EMAIL=... ADMIN_PASSWORD=... ./pod-request-counts.sh
#
# 前置：叢集中存在名為 curl-probe 的 Pod（見下方 ensure_probe），且該管理員帳號存在。

set -euo pipefail
export MSYS_NO_PATHCONV=1

NAMESPACE="${NAMESPACE:-flashsale}"
CONTEXT="${CONTEXT:-rancher-desktop}"
GATEWAY="${GATEWAY:-https://localhost:8443}"
ADMIN_EMAIL="${ADMIN_EMAIL:-metrics-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?ADMIN_PASSWORD is required}"
KUBECTL="${KUBECTL:-kubectl}"

k() { "$KUBECTL" --context "$CONTEXT" -n "$NAMESPACE" "$@"; }

ensure_probe() {
  if ! k get pod curl-probe >/dev/null 2>&1; then
    k run curl-probe --restart=Never --image=curlimages/curl:8.10.1 --command -- sleep 36000 >/dev/null
    k wait --for=condition=ready pod/curl-probe --timeout=120s >/dev/null
  fi
}

login() {
  curl -sk -X POST "$GATEWAY/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" |
    sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

ensure_probe
TOKEN="$(login)"
if [ -z "$TOKEN" ]; then
  echo "login failed for $ADMIN_EMAIL; cannot read admin-only metrics" >&2
  exit 1
fi

total=0
declare -a names counts
while read -r name ip; do
  [ -z "$ip" ] && continue
  body="$(k exec curl-probe -- curl -s -H "Authorization: Bearer $TOKEN" \
    "http://$ip:8080/actuator/metrics/http.server.requests" || true)"
  count="$(printf '%s' "$body" | sed -n 's/.*"statistic":"COUNT","value":\([0-9.]*\).*/\1/p')"
  count="${count%.*}"
  [ -z "$count" ] && count=0
  names+=("$name")
  counts+=("$count")
  total=$((total + count))
done < <(k get pods -l app=backend -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.podIP}{"\n"}{end}')

printf '%-34s %10s %8s\n' POD REQUESTS SHARE
for i in "${!names[@]}"; do
  if [ "$total" -gt 0 ]; then
    share="$(awk -v c="${counts[$i]}" -v t="$total" 'BEGIN { printf "%.1f%%", (c / t) * 100 }')"
  else
    share="n/a"
  fi
  printf '%-34s %10s %8s\n' "${names[$i]}" "${counts[$i]}" "$share"
done
printf '%-34s %10s %8s\n' TOTAL "$total" '100.0%'
