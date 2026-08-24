#!/usr/bin/env bash
# 在 EC2 單節點 k3s 上部署一個已經通過 CI 的 commit。由 GitHub Actions 經 SSM 呼叫，
# 也可以在機器上手動跑：sudo ./scripts/k8s/ec2-deploy.sh <git-sha>
set -euo pipefail

SHA="${1:?usage: ec2-deploy.sh <git-sha>}"
export KUBECONFIG="${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}"
OVERLAY=k8s/overlays/aws

# sed 會弄髒 working tree；呼叫端每次都先 git checkout -f，所以不需要還原。
sed -i "s/PLACEHOLDER/${SHA}/g" "${OVERLAY}/kustomization.yaml"

kubectl apply -k "${OVERLAY}"

for deployment in backend frontend nginx; do
  kubectl -n flashsale rollout status "deploy/${deployment}" --timeout=300s
done

# 自簽憑證所以帶 -k；這是整條路徑（Nginx 8443 -> frontend）唯一的 end-to-end 檢查。
curl -fsS -k -o /dev/null https://localhost/

echo "deployed ${SHA}"
