# Rancher Desktop (k3s) 部署模式設計規格

> **本文件的四個項目已被
> [Week 8 設計規格](./2026-09-13-flashsale-k8s-microservices-design.md) 取代，以新規格為準：**
>
> 1. **副本數**：本文件規劃 backend `replicas: 2`；實際為 `replicas: 3`（Week 8 P1 實測後定案）。
> 2. **資源配額**：本文件規劃 `requests: 256Mi/250m`；實際為 `requests: 500m/1Gi`、
>    `limits: 2/2Gi`。256Mi 對 Spring Boot 容易 OOM。
> 3. **postgres 的工作負載型別**：本文件規劃 Deployment 加 PVC；實際為 StatefulSet 搭配
>    headless Service 與 `volumeClaimTemplates`，redis 與 rabbitmq 亦同。
> 4. **排程重複執行的解法**：本文件設計以 Postgres `pg_try_advisory_lock` 實作
>    `AdvisoryLockRunner`。該設計**不實作**，Week 8 P2 改用 Redisson，理由見新規格的
>    「分散式鎖」一節。
>
> 本文件指出的「排程重複執行」問題本身完全正確，而且已在三副本環境下取得實測證據，
> 見 [排程重複執行證據](../../portfolio/scheduler-duplication-evidence.md)。

## 目標

在現有 Docker Compose 之外，新增可用 Rancher Desktop 內建 k3s 跑起來的部署模式。除 backend 外
全部單一 replica；backend 開兩個 pod，並修掉多 pod 下會重複執行的排程副作用。不引入 Helm／
Kustomize，維持純 K8s YAML manifests，與現有 `nginx/`、`backend/`、`frontend/` 目錄風格一致。

## 設計

### 目錄與部署腳本

- 新增 `k8s/` 目錄，每個 service 一個子目錄（`postgres/`、`redis/`、`rabbitmq/`、`mailpit/`、
  `zipkin/`、`backend/`、`frontend/`、`nginx/`），內含 `deployment.yaml` + `service.yaml`，
  postgres 另加 `pvc.yaml`，backend 另加 `configmap.yaml`；根目錄 `namespace.yaml`（namespace:
  `flashsale`）。
- 新增 `scripts/k8s-deploy.sh`（Git Bash，比照 `scripts/demo-data.sh` 風格），流程：
  1. `docker build` 產生 `flashsale-backend:local`／`flashsale-frontend:local`／
     `flashsale-nginx:local`。Rancher Desktop 設為 dockerd (moby) 模式時 k3s 直接看得到本機
     image，manifests 用 `imagePullPolicy: IfNotPresent` 搭配這三個 tag，不需 push／import。
  2. `kubectl create namespace flashsale`（已存在則略過，不視為失敗）。
  3. 讀取專案根目錄 `.env`，用
     `kubectl create secret generic flashsale-secrets --dry-run=client -o yaml | kubectl apply -f -`
     產生／更新 Secret（`JWT_PRIVATE_KEY`、`JWT_PUBLIC_KEY`、`GMAIL_USERNAME`、
     `GMAIL_APP_PASSWORD`、`POSTGRES_PASSWORD`），可重複執行。
  4. 用既有 `nginx/certs/localhost.crt`／`localhost.key` 以同樣 dry-run+apply 手法建立
     `kubectl create secret tls flashsale-tls`。
  5. `kubectl apply -f k8s/ -R -n flashsale`。
  6. 對 backend／frontend／nginx 執行 `kubectl rollout status` 確認就緒後印出存取網址
     （`https://localhost:8443`）。

### 各 Service 設計

- **postgres**：Deployment（非 StatefulSet，單 replica 已足夠）+ PVC（用 k3s 內建
  `local-path` StorageClass，不需額外安裝 provisioner）+ ClusterIP Service `postgres`。
- **redis／rabbitmq／mailpit／zipkin**：單 replica Deployment + ClusterIP Service，
  liveness/readiness probe 比照 compose 現有 healthcheck 指令（exec 或對應 HTTP endpoint）改寫。
- **backend**：`replicas: 2`。
  - env 分兩種來源：ConfigMap（`SPRING_PROFILES_ACTIVE=docker`、各 service 的 k8s 短 DNS 名稱如
    `postgres`／`redis`／`rabbitmq`／`mailpit`／`zipkin`、`APP_HEALTH_*` 系列）+ Secret
    `flashsale-secrets`。
  - `readinessProbe` → `/actuator/health/readiness`（沿用既有 db/redis/rabbit 檢查群組）。
  - `livenessProbe` → `/actuator/health/liveness`。
  - `startupProbe` → 同 readiness endpoint，給寬鬆 `failureThreshold`，避免 Flyway migration
    與外部連線檢查還沒跑完就被 liveness 判定失敗殺掉。
  - JWT 無狀態認證，兩 pod 間不需要 sticky session；ClusterIP Service 直接負載平衡即可。
  - 資源預設 `requests: 256Mi/250m`、`limits: 512Mi/500m`（demo 用途保守值）。
  - 兩 pod 同時啟動都會各自跑 Flyway migration；Flyway 本身對 Postgres 有遷移鎖保護併發啟動，
    不需要額外處理。
- **frontend**：單 replica Deployment + ClusterIP Service，readiness 打 `/internal-health`。
- **nginx**：單 replica Deployment，掛載 `flashsale-tls` Secret 到 `/etc/nginx/certs`（取代
  compose 原本的 host volume mount）；Service 型別 `LoadBalancer`，對外 port 8443 —— Rancher
  Desktop 內建 servicelb 會如同 compose 的 port mapping 一樣把 `localhost:8443` 轉進來。

### 排程重複執行修正（backend 兩 pod 前置條件）

現有 5 個 `@Scheduled` 排程中，Outbox 發布已用 `SKIP LOCKED` 處理並發安全；其餘 4 個
（`InventoryReconciliationScheduler`、`NotificationRetryScheduler`、`PaymentTimeoutScheduler`、
`ApiAuditRetentionScheduler`）沒有分散式鎖，backend 開兩 pod 後會同時各跑一次。

新增 `com.flashsale.common.scheduling.AdvisoryLockRunner`（用 Spring Data JPA 已間接帶入的
`JdbcTemplate`，不加新依賴）：

```java
boolean runIfLocked(long lockKey, Runnable task)
```

內部用 Postgres `pg_try_advisory_lock(lockKey)`（non-blocking，拿不到鎖直接跳過本次執行，不
排隊等待）執行 `task`，`try/finally` 確保一定呼叫 `pg_advisory_unlock(lockKey)`；unlock 失敗
只記 log，不影響主流程或拋出例外。

4 個排程各給一個固定 lock key 常數，方法內容改成：

```java
@Scheduled(fixedDelay = 60000)
public void reconcileActiveFlashSales() {
    advisoryLockRunner.runIfLocked(LOCK_KEY, this::doReconcile);
}
```

## 驗收

- `AdvisoryLockRunner` 單元測試：mock `JdbcTemplate`，驗證拿到鎖時執行 task 並釋放鎖、拿不到
  鎖時不執行 task、task 拋例外時鎖仍會被釋放。
- 4 個排程既有測試調整為驗證「包一層後行為不變」（mock runner 直接放行）。
- `k8s/` manifests 沒有自動化測試；驗證方式是手動執行 `scripts/k8s-deploy.sh` 後：
  - `kubectl get pods -n flashsale` 全部 Running/Ready，backend 兩個 pod 都 Ready。
  - 瀏覽器打 `https://localhost:8443` 走完一次購買流程，確認與 Compose 環境行為一致。
  - `kubectl logs` 確認兩個 backend pod 不會同時各印一次同一次排程執行的 log。
