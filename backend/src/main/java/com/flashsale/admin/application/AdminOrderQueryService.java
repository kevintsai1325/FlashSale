package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.AdminOrderDetail;
import com.flashsale.admin.application.dto.AdminOrderItemView;
import com.flashsale.admin.application.dto.AdminOrderSummary;
import com.flashsale.admin.application.dto.ApiAuditLogView;
import com.flashsale.admin.application.dto.OrderStatusHistoryView;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.admin.application.dto.PurchaseRequestView;
import com.flashsale.common.client.OrderServiceClient;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.common.web.ApiAuditLogJpaRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 後台訂單清單／詳情的 **BFF**（P5）：端點留在 platform，資料來自 order-service。
 *
 * 為什麼端點不跟著訂單搬走：這個畫面要的不只是訂單 —— 它還要把 platform 自己的
 * `api_audit_logs` 併進去。把端點搬到 order-service，就得換成 order-service 去查
 * platform 的稽核表，等於把剛拆掉的耦合原封不動搬個方向。
 *
 * 「相關的稽核紀錄」仍然是刻意的時間窗比對而不是自動 join：`orders` 沒有 `trace_id` 欄位，
 * 兩邊沒有可以對上的鍵。這裡用訂單進入 `PENDING_PAYMENT` 的時間（拿不到就退回 `createdAt`）
 * 前後 {@link #CORRELATION_WINDOW} 內、同一個 userId 的稽核列 —— 是給後台跳轉用的線索，
 * 不是保證精確的關聯。
 *
 * 拆分後的差別：`statusHistory` 由 order-service 提供，所以「PENDING_PAYMENT 的時間」
 * 也是跨服務拿到的，拿不到時退回 `createdAt` 這條退路因此比拆分前更常被走到。
 */
@Service
public class AdminOrderQueryService {

    private static final Duration CORRELATION_WINDOW = Duration.ofMinutes(5);

    private final OrderServiceClient orderServiceClient;
    private final ApiAuditLogJpaRepository apiAuditLogRepository;

    public AdminOrderQueryService(OrderServiceClient orderServiceClient,
                                   ApiAuditLogJpaRepository apiAuditLogRepository) {
        this.orderServiceClient = orderServiceClient;
        this.apiAuditLogRepository = apiAuditLogRepository;
    }

    public PagedResult<AdminOrderSummary> list(String status, int page, int size) {
        OrderServiceClient.PagedOrders orders = orderServiceClient.orders(status, page, size);
        List<AdminOrderSummary> content = orders.content().stream()
            .map(o -> new AdminOrderSummary(o.id(), o.orderNo(), o.userId(), o.totalAmount(), o.status(), o.createdAt()))
            .toList();
        return new PagedResult<>(content, orders.totalElements(), page, size);
    }

    public AdminOrderDetail getDetail(Long orderId) {
        OrderServiceClient.OrderDetail order = orderServiceClient.orderDetail(orderId)
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "訂單 " + orderId + " 不存在"));

        List<AdminOrderItemView> items = order.items().stream()
            .map(i -> new AdminOrderItemView(i.productId(), i.quantity(), i.unitPrice()))
            .toList();

        List<OrderStatusHistoryView> statusHistory = order.statusHistory().stream()
            .map(h -> new OrderStatusHistoryView(h.fromStatus(), h.toStatus(), h.changedAt()))
            .toList();

        Instant anchor = order.statusHistory().stream()
            .filter(h -> "PENDING_PAYMENT".equals(h.toStatus()))
            .map(OrderServiceClient.StatusHistoryView::changedAt)
            .findFirst()
            .orElse(order.createdAt());

        List<ApiAuditLogView> relatedApiLogs = apiAuditLogRepository
            .findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                order.userId(), anchor.minus(CORRELATION_WINDOW), anchor.plus(CORRELATION_WINDOW))
            .stream()
            .map(ApiAuditLogView::from)
            .toList();

        // 訂單存在本身就是搶購成功的證明（訂單只由建單成功那條路徑產生），
        // 所以狀態是推導出來的，不值得為它再跨一次服務去問 purchase-service。
        PurchaseRequestView purchaseRequest = order.purchaseRequestId() == null
            ? null : new PurchaseRequestView(order.purchaseRequestId(), "SUCCEEDED", order.id());

        return new AdminOrderDetail(order.id(), order.orderNo(), order.userId(), order.totalAmount(),
            order.status(), order.paymentDueAt(), order.createdAt(), items, purchaseRequest,
            statusHistory, relatedApiLogs);
    }
}
