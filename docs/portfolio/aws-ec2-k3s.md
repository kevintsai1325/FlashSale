# AWS EC2 單節點 k3s 部署

FlashSale 在單台 EC2 上以 k3s 執行，並由 GitHub Actions 推動 CD 的操作紀錄。
Docker Compose 的[快速開始](../../README.md#快速開始)仍是最短的本機啟動方式；
[Rancher Desktop 基準](./k3s-baseline.md)記錄的是本機 k3s 環境，與這份文件互不取代。

> 這是作品集的部署練習，不是上線中的商業系統。下面每個數字都取自一次真實執行的輸出，
> 不是容量規劃或 SLA 承諾。

## 證據狀態（截至 2026-08-25T01:40:50+00:00）

| 項目 | 狀態 | 證據／說明 |
|---|---|---|
| 節點 | 已確認 | `ip-172-31-41-9.ec2.internal` Ready／control-plane，`v1.36.3+k3s1`，containerd `2.3.2-k3s2`，Amazon Linux 2023.12.20260817，kernel `6.18.41-94.142.amzn2023.x86_64` (amd64) |
| 機器規格 | 已確認 | EC2 t3.medium（2 vCPU / 3.7 GiB 可用），30 GiB gp3，us-east-1，Elastic IP `54.90.154.64` |
| Workloads | 已確認 | 5 個 Deployment、3 個 StatefulSet 全部 `1/1`；3 個 PVC 全部 `Bound` |
| 對外路由 | 已確認 | `service/nginx` 型別 `LoadBalancer`，EXTERNAL-IP `172.31.41.9`，`80:32054/TCP`、`443:32308/TCP`（k3s 內建 ServiceLB） |
| Image 溯源 | 已確認 | 8 個 Pod 的 resolved `imageID` 全部記錄於下方 |
| CD | 已確認 | Release run `32797444058` 全綠，`deployed 08e8620c5d07637985abd69abc802d561156f915`、`status: Success` |
| 端到端回應 | 已確認 | `ec2-deploy.sh` 最後的 `curl -fsS -k https://localhost/` 通過（不通過就不會印 `deployed`） |
| **壓力測試** | **未執行** | 本節所有數字都是部署狀態，沒有任何吞吐或延遲量測。[負載特性報告](./performance-report.md)的數字來自本機 Compose，不適用於這台機器 |
| **TLS 憑證** | **自簽** | SAN 含 `DNS:localhost, IP:127.0.0.1, IP:54.90.154.64`，瀏覽器會警告簽發者不受信任。沒有申請公開 CA 憑證 |
| **高可用** | **無** | 單節點、每個服務單 replica、PVC 走 `local-path`（綁在該節點的 EBS 上）。節點掛掉即全數不可用 |

### Resolved image IDs

```
backend    ghcr.io/kevintsai1325/flashsale-backend@sha256:2b0fe5583b7d9fc2e02cdb6dd12b732bde1fc9be0e89cbb50a183a4d0f392ed9
frontend   ghcr.io/kevintsai1325/flashsale-frontend@sha256:0762afe79fdfb751656fe07ca03fdbe6b01465c07f31762ccf97e844a7d6992e
nginx      ghcr.io/kevintsai1325/flashsale-nginx@sha256:00940e971efee2976f5f9c7137c7e626a2f67acef7e479669ab19108f1dbec85
postgres   docker.io/library/postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685
rabbitmq   docker.io/library/rabbitmq@sha256:606d8c0d6b3c18d1da9afc53bc7cdb2a8d5486df91b5a9830e9e07626c9ae281
redis      docker.io/library/redis@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf
mailpit    docker.io/axllent/mailpit@sha256:b004cc0672d0692f097ba8ab9e5956ae08eeafee3d0a6bf2ab239750101ac538
zipkin     docker.io/openzipkin/zipkin@sha256:d17e856dcbba7ffeefbbfc252f89ab78a4ab6faed47e646d46daad78f91b5ee2
```

三個 `flashsale-*` image 由 GitHub Actions 建置後推上 GHCR，tag 是被 CI 測過的 commit sha；
其餘為上游 image。上游用的仍是可變動的 major/minor tag（`postgres:16-alpine` 等），
所以流程可重跑不等於 bit-for-bit 可重現——上面的 digest 才是這次執行的真實依據。

## 架構與限制

- 對外只開 80 與 443。**沒有開 SSH**：進機器走 SSM Session Manager，CD 走 SSM Send-Command。
- EC2 沒有存任何 AWS 金鑰，GitHub Actions 也沒有。CD 以 GitHub OIDC 換取臨時憑證。
- k3s 以 `--disable traefik` 安裝：入口仍是既有的 Nginx gateway，不由 Traefik 取代。
- `k8s/base` 是本機契約（`:local` image、`imagePullPolicy: Never`、8080/8443）。
  EC2 的差異全部收在 `k8s/overlays/aws`，base 不因雲端部署而改動。

### overlay 改了什麼

| 項目 | base | overlay |
|---|---|---|
| image | `flashsale-*:local` | `ghcr.io/kevintsai1325/flashsale-*:<sha>` |
| `imagePullPolicy` | `Never` | `IfNotPresent` |
| Nginx Service port | 8080 / 8443 | 80 / 443（`targetPort` 不變） |
| 更新策略 | 未指定（預設 `maxSurge: 25%`） | `maxSurge: 0`、`maxUnavailable: 1` |

最後一項是實測逼出來的。節點 requests 已承諾約 2.6 GiB，backend 單一 Pod 就 request 1 GiB，
預設的「先起新的再殺舊的」在滾動更新瞬間需要多一份 backend 的配額，新 Pod 直接 `Pending`、
舊 Pod 收不掉，`kubectl rollout status` 到 300 秒逾時。單節點單 replica 本來就沒有可用性可保，
改成先殺後起；代價是更新期間會短暫斷線。

節點記憶體實測：total 3.7 GiB、used 1.6 GiB、available 1.8 GiB、**無 swap**。
排程看的是 requests 不是實際用量，所以「用量只有 47%」跟「排不進去」可以同時成立。

## CD 管線

```
git push main
  → CI（後端 169 + 前端 70 個測試）
  → Release: 三個 image 平行建置並推上 GHCR，tag = 該 commit sha
  → GitHub OIDC → sts:AssumeRoleWithWebIdentity → 臨時憑證
  → ssm:SendCommand → EC2 執行 scripts/k8s/ec2-deploy.sh <sha>
      git checkout -f <sha> → kubectl apply -k k8s/overlays/aws
      → rollout status ×3 → curl https://localhost/
```

IAM 角色的信任政策把 `sub` 鎖在本 repo 的 main 分支，權限只有指定 instance 上的
`ssm:SendCommand` 與讀取執行結果。

### 建置時實際踩到的四個問題

留在這裡是因為它們都不會出現在教學文件裡，但每一個都會讓管線靜默失敗。

1. **`sudo` 與 SSM 的 PATH 不含 `/usr/local/bin`**，而 k3s 的 `kubectl` 就裝在那裡。
   `ec2-deploy.sh` 因此在第一個 kubectl 就中止。修法是在腳本內自行補 PATH。
2. **Windows 上 `chmod +x` 不會進 git**（`core.filemode` 為 false）。
   兩支 `.sh` 以 `100644` 入庫，SSM 的 `exec ./scripts/k8s/ec2-deploy.sh` 直接 Permission denied。
   用 `git update-index --chmod=+x` 修正。
3. **GitHub 的 OIDC `sub` 帶了數字 ID**：實際送出的是
   `repo:kevintsai1325@107104253/FlashSale@1328560322:ref:refs/heads/main`，
   不是文件上常見的 `repo:owner/repo:ref:refs/heads/main`。信任政策用舊格式做 `StringEquals`
   永遠不匹配，錯誤訊息只有一句 `Not authorized to perform sts:AssumeRoleWithWebIdentity`。
   定位方式是在 workflow 裡把 ID token 的 payload 解出來印 claim。
4. **`aws ssm wait command-executed` 上限只有 100 秒**（20 次 × 5 秒），
   但 `ec2-deploy.sh` 光三個 `rollout status` 就允許 300 秒，於是 workflow 在部署還在跑時就放棄。
   改成自行輪詢到終態。

## 重現步驟

### 一次性

EC2：t3.medium、Amazon Linux 2023、30 GiB gp3、Security Group 只開 80/443（不開 22）、
掛帶 `AmazonSSMManagedInstanceCore` 的 instance profile、綁 Elastic IP。

機器上（`sudo -i` 後執行，root 的 PATH 才含 `/usr/local/bin`）：

```bash
dnf install -y git
curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable traefik" sh -
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

git clone https://github.com/kevintsai1325/FlashSale.git /opt/flashsale
cd /opt/flashsale

CERT_SAN="DNS:localhost,IP:127.0.0.1,IP:<你的 EIP>" ./nginx/certs/generate-cert.sh

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /root/jwt-private.pem
openssl rsa -pubout -in /root/jwt-private.pem -out /root/jwt-public.pem

PG_PW=$(openssl rand -base64 24); MQ_PW=$(openssl rand -base64 24)
kubectl apply -f k8s/base/namespace.yaml
kubectl -n flashsale create secret generic flashsale-secrets \
  --from-literal=POSTGRES_PASSWORD="$PG_PW" \
  --from-literal=SPRING_RABBITMQ_PASSWORD="$MQ_PW" \
  --from-file=JWT_PRIVATE_KEY=/root/jwt-private.pem \
  --from-file=JWT_PUBLIC_KEY=/root/jwt-public.pem
kubectl -n flashsale create secret tls flashsale-local-tls \
  --cert=nginx/certs/localhost.crt --key=nginx/certs/localhost.key
```

JWT 私鑰必須是 PKCS#8（`openssl genpkey` 的輸出）；`genrsa` 產的是 PKCS#1，
`JwtKeyConfig` 只剝除 `BEGIN/END PRIVATE KEY` 標頭，格式不符會讓 backend 起不來。

兩組隨機密碼與三個 PVC 是**同一組必須共同保留的狀態**。Secret 遺失但 PVC 還在，資料庫就開不起來。

AWS 端另需：IAM OIDC 身分提供者（`token.actions.githubusercontent.com`，audience `sts.amazonaws.com`）、
一個信任該提供者且 `sub` 鎖定本 repo main 分支的角色。
GitHub 端設定 secrets `AWS_ROLE_ARN`、`EC2_INSTANCE_ID` 與 variable `AWS_REGION`。

### 之後每次

推 main 即可。要手動部署某個已推上 GHCR 的 sha：

```bash
cd /opt/flashsale && git pull
./scripts/k8s/ec2-deploy.sh <git-sha>
```

### 擷取本文件的證據

```bash
date -Is; uptime -s
kubectl version | head -3
kubectl get nodes -o wide
kubectl -n flashsale get deploy,statefulset,svc,pvc
kubectl -n flashsale get pods -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[0].imageID}{"\n"}{end}'
free -h
```

以上指令都不會輸出 Secret 內容。

## 已知的待辦

- **rabbitmq-0 在一次部署期間重啟過一次**，`lastState.terminated.reason` 是 `Completed`（正常結束、非 OOMKilled），
  原因尚未查明。記錄在此以免被當成沒發生過。
- **成本**：t3.medium 約 US$30/月，公有 IPv4 US$3.6/月，30 GiB gp3 US$2.4/月。
  停機時只付 EBS 約 US$3/月。停機再開機時 Pod 物件與 PVC 都會保留，容器重啟一次。
- **Rancher Desktop 本機環境仍未跑通**，見[該文件的證據狀態](./k3s-baseline.md#證據狀態截至-2026-08-21)。
  這次的 EC2 結果不能拿來宣稱本機也驗證過。
