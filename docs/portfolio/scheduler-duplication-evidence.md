# 多副本下排程任務重複執行的實測證據

這份文件記錄一個在單副本下永遠看不到、但把 `backend` 擴展到三副本的那一刻就會出現的正確性缺陷：
沒有互斥保護的 `@Scheduled` 排程任務會在每個副本上各執行一次，而 `PaymentTimeoutScheduler`
的重複執行會**直接摧毀系統最核心的「不超賣」保證**。

同一次實驗也證明了 `OutboxPublisher` 因為使用 `FOR UPDATE SKIP LOCKED`，在相同條件下完全安全。
兩者的對照是這份文件的重點：**並非所有互斥都需要分散式鎖，但沒有互斥就一定會出事。**

## 量測環境

| 項目 | 值 |
|---|---|
| 叢集 | Rancher Desktop k3s，單節點 `mocuo`，v1.36.3+k3s1 |
| 容器執行環境 | `docker://29.5.3`（k3s 以 `--docker` 啟動） |
| 機器 | 24 邏輯核心、31.6 GB RAM，k6 與叢集共用同一台實體機 |
| backend 副本數 | 3 |
| 量測時間 | 2026-09-13 06:25–06:28 UTC |

## 受影響的排程任務

`backend` 共有 5 個 `@Scheduled` 排程。其中只有一個具備併發保護：

| 排程任務 | 頻率 | 互斥機制 | 三副本下的結果 |
|---|---|---|---|
| `OutboxPublisher` | `fixedDelay = 500` | `SELECT ... FOR UPDATE SKIP LOCKED` | **安全**，本次實驗實測無重複 |
| `PaymentTimeoutScheduler` | `fixedDelay = 30000` | 無 | **重複執行，導致庫存超賣** |
| `NotificationRetryScheduler` | `fixedDelay = 60000` | 無 | 會重複寄送通知 |
| `InventoryReconciliationScheduler` | `fixedDelay = 60000` | 無 | 多副本同時比對並修正 Redis 與 DB |
| `ApiAuditRetentionScheduler` | `cron = 0 0 3 * * *` | 無 | 重複刪除，浪費資源 |

本次實驗針對後果最嚴重的 `PaymentTimeoutScheduler` 取證。

## 缺陷成因

`PaymentTimeoutScheduler` 讀出逾時未付款的訂單，逐筆交給 `OrderCompensationService.markExpired`：

```java
@Scheduled(fixedDelay = 30000)
public void expireOverduePayments() {
    List<Order> overdue = orderRepository.findPendingPaymentPastDue(Instant.now());
    for (Order order : overdue) {
        compensationService.markExpired(order);
    }
}
```

`findPendingPaymentPastDue` 最終是 `findByStatusAndPaymentDueAtBefore`，**沒有任何鎖**。
`Order` 實體也**沒有 `@Version` 樂觀鎖欄位**。因此三個副本可以同時讀到同一批
`PENDING_PAYMENT` 訂單，各自進入 `compensate()`：

```java
private void compensate(Order order) {
    orderRepository.save(order);
    orderStatusHistoryRepository.record(order.getId(), OrderStatus.PENDING_PAYMENT, order.getStatus());
    ...
    Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(purchaseRequest.getFlashSaleId())...;
    inventory.release(quantity);          // <— 每個副本各回補一次
    inventoryRepository.save(inventory);
    outboxWriter.write(..., EventTypes.STOCK_RELEASE_REQUESTED, ...);
}
```

`findByFlashSaleIdForUpdate` 的悲觀鎖只保證三筆交易**依序**執行，不會阻止它們**各做一次**。
結果是同一筆訂單的庫存被回補三次。

## 實驗方法

1. 以 k6 壓測產生 30 筆成功訂單，狀態皆為 `PENDING_PAYMENT`，庫存耗盡（`available_quantity = 0`）。
2. 同時刪除三個 backend Pod：`kubectl delete pods -l app=backend`。重建後三個 Pod 的
   `startTime` 皆為 `2026-09-13T06:27:18Z`（同一秒），因此三者的 `fixedDelay` 計時器對齊，
   確保重複執行的競態一定會發生，而不是碰運氣。
3. 將全部訂單改為已逾時：`UPDATE orders SET payment_due_at = now() - interval '1 hour' WHERE status='PENDING_PAYMENT';`
4. 等待 45 秒（排程週期為 30 秒），再檢查資料。

## 結果

| 指標 | 實驗前 | 實驗後 | 正確值 | 判定 |
|---|---|---|---|---|
| `EXPIRED` 訂單數 | 0 | 30 | 30 | 正常 |
| `order_status_history` 筆數 | 30 | **117** | 60 | **多出 57 筆** |
| `inventory.available_quantity` | 0 | **87** | 30 | **超出總庫存 57 件** |
| `inventory.total_quantity` | 30 | 30 | 30 | — |
| `StockReleaseRequested` 事件數 | 0 | **87** | 30 | **多出 57 筆** |

每筆訂單被寫入的 `EXPIRED` 狀態歷史筆數分佈：

| 每筆訂單的處理次數 | 訂單數 |
|---|---|
| 2 次 | 3 |
| **3 次** | **27** |

30 筆訂單中有 27 筆被**三個副本各處理了一次**。

同一筆訂單的狀態歷史明細（訂單 1 與 2）：

```
 order_id |   from_status   |    to_status    |          changed_at
----------+-----------------+-----------------+-------------------------------
        1 |                 | PENDING_PAYMENT | 2026-09-13 06:25:35.331966+00
        1 | PENDING_PAYMENT | EXPIRED         | 2026-09-13 06:27:57.529946+00
        1 | PENDING_PAYMENT | EXPIRED         | 2026-09-13 06:27:57.554929+00
        2 |                 | PENDING_PAYMENT | 2026-09-13 06:25:35.354165+00
        2 | PENDING_PAYMENT | EXPIRED         | 2026-09-13 06:27:57.567379+00
        2 | PENDING_PAYMENT | EXPIRED         | 2026-09-13 06:27:57.590936+00
```

同一筆訂單在 **25 毫秒內**被標記為 `EXPIRED` 兩次。

### 為什麼這是最嚴重的一項

`available_quantity` 變成 **87，而 `total_quantity` 只有 30**。庫存憑空多出 57 件。

這代表 FlashSale 整個專案最核心的保證 ——「不超賣」—— 在多副本部署下**完全失效**。
接下來的 57 個買家會買到不存在的商品。單副本壓測時累積的所有「零超賣」證據，
在把 `replicas` 從 1 改成 3 的那一刻全部作廢。

## 對照組：`OutboxPublisher` 在相同條件下是安全的

同一次實驗、同樣三個副本、同一段時間內：

| 指標 | 值 | 判定 |
|---|---|---|
| `outbox_events` 總數 | 117 | — |
| 已發佈（`published_at` 非空） | 117 | 全部發佈完成 |
| 未發佈 | 0 | 無遺漏 |
| `consumed_messages` 總筆數 | 117 | — |
| `consumed_messages` 相異 `message_id` | 117 | **無重複消費** |

`OutboxPublisher` 唯一的差別是它的查詢帶了 `FOR UPDATE SKIP LOCKED`：

```sql
SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED
```

資料庫的行鎖讓每一列只會被一個副本取走，其餘副本直接跳過。**這個做法不需要任何外部協調服務，
也不需要分散式鎖。** 能用資料庫行鎖解決的互斥，就不該引入 Redis 或 ZooKeeper。

## 結論與後續

1. 四個沒有互斥保護的排程任務必須加上互斥機制，其中 `PaymentTimeoutScheduler` 是正確性缺陷，
   不是效能問題。
2. 互斥機制的選擇不是只有分散式鎖一種。`OutboxPublisher` 證明了「逐列取用」型的工作可以用
   `SKIP LOCKED` 解決；但「整批掃描後逐筆處理」型的排程無法這樣改寫，需要真正的互斥。
3. 這四個排程的修正屬於 Week 8 P2 的範圍，設計見
   [Week 8 設計規格](../superpowers/specs/2026-09-13-flashsale-k8s-microservices-design.md#分散式鎖p2)。
4. P2 完成後，本文件的實驗必須能重跑並得到「每筆訂單恰好一次、`available_quantity` 等於
   `total_quantity`」的結果，作為修正有效的證明。

## 重現步驟

```bash
# 1. 部署並擴展到三副本
powershell -File scripts/k8s/deploy.ps1
kubectl -n flashsale scale deployment/backend --replicas=3

# 2. 產生 30 筆 PENDING_PAYMENT 訂單
cat load-tests/benchmark/fixtures.sql | kubectl -n flashsale exec -i statefulset/postgres -- \
  psql -U flashsale -d flashsale -v stock=30
kubectl apply -f load-tests/k8s/k6-job.yaml
kubectl -n flashsale wait --for=condition=complete job/k6-purchase-flow --timeout=600s

# 3. 對齊三個副本的排程計時器
kubectl -n flashsale delete pods -l app=backend
kubectl -n flashsale rollout status deployment/backend

# 4. 讓訂單逾時，等待排程觸發
kubectl -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c \
  "UPDATE orders SET payment_due_at = now() - interval '1 hour' WHERE status='PENDING_PAYMENT';"
sleep 45

# 5. 檢查
kubectl -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c \
  "SELECT rows_per_order, count(*) AS orders FROM (
     SELECT order_id, count(*) AS rows_per_order FROM order_status_history
     WHERE to_status='EXPIRED' GROUP BY order_id) t
   GROUP BY rows_per_order ORDER BY rows_per_order;"
kubectl -n flashsale exec -i statefulset/postgres -- psql -U flashsale -d flashsale -c \
  "SELECT total_quantity, available_quantity FROM inventory WHERE flash_sale_id=1;"
```
