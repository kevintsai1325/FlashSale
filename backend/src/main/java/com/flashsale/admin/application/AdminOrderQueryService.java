package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.AdminOrderDetail;
import com.flashsale.admin.application.dto.AdminOrderItemView;
import com.flashsale.admin.application.dto.AdminOrderSummary;
import com.flashsale.admin.application.dto.ApiAuditLogView;
import com.flashsale.admin.application.dto.OrderStatusHistoryView;
import com.flashsale.admin.application.dto.PagedResult;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.common.web.ApiAuditLogJpaRepository;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.dto.PurchaseRequestView;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.OrderStatusHistory;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Admin order list/detail. {@code getDetail}'s "related audit logs" are a manual, time-window
 * cross-reference rather than an automatic join: {@code orders} stores no {@code trace_id}
 * (adding one is out of scope for this plan — no new Flyway migration), so there is no key to
 * join {@code api_audit_logs} against. Instead this looks up audit rows for the order's
 * {@code userId} that fall within {@link #CORRELATION_WINDOW} of the order's
 * {@code PENDING_PAYMENT} status-history timestamp (the moment the order — and, in practice, the
 * request that created it — came into being), falling back to {@code order.getCreatedAt()} if
 * that history row is missing. This is intentionally an honest "logs around this time" hint for
 * the admin UI, not a guaranteed-exact correlation.
 */
@Service
public class AdminOrderQueryService {

    private static final Duration CORRELATION_WINDOW = Duration.ofMinutes(5);

    private final OrderRepository orderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final ApiAuditLogJpaRepository apiAuditLogRepository;

    public AdminOrderQueryService(OrderRepository orderRepository,
                                   PurchaseRequestRepository purchaseRequestRepository,
                                   OrderStatusHistoryRepository orderStatusHistoryRepository,
                                   ApiAuditLogJpaRepository apiAuditLogRepository) {
        this.orderRepository = orderRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.apiAuditLogRepository = apiAuditLogRepository;
    }

    public PagedResult<AdminOrderSummary> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Order> result = orderRepository.findAllPaged(pageable);

        List<AdminOrderSummary> content = result.getContent().stream()
            .map(o -> new AdminOrderSummary(o.getId(), o.getOrderNo(), o.getUserId(), o.getTotalAmount(),
                o.getStatus().name(), o.getCreatedAt()))
            .toList();

        return new PagedResult<>(content, result.getTotalElements(), page, size);
    }

    public AdminOrderDetail getDetail(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "Order " + orderId + " does not exist"));

        List<AdminOrderItemView> items = order.getItems().stream()
            .map(i -> new AdminOrderItemView(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
            .toList();

        PurchaseRequestView purchaseRequest = purchaseRequestRepository.findByOrderId(orderId)
            .map(AdminOrderQueryService::toPurchaseRequestView)
            .orElse(null);

        List<OrderStatusHistory> historyEntities = orderStatusHistoryRepository.findByOrderId(orderId);
        List<OrderStatusHistoryView> statusHistory = historyEntities.stream()
            .map(h -> new OrderStatusHistoryView(
                h.getFromStatus() == null ? null : h.getFromStatus().name(),
                h.getToStatus().name(),
                h.getChangedAt()))
            .toList();

        Instant anchor = historyEntities.stream()
            .filter(h -> h.getToStatus() == OrderStatus.PENDING_PAYMENT)
            .map(OrderStatusHistory::getChangedAt)
            .findFirst()
            .orElse(order.getCreatedAt());

        List<ApiAuditLogView> relatedApiLogs = apiAuditLogRepository
            .findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(
                order.getUserId(), anchor.minus(CORRELATION_WINDOW), anchor.plus(CORRELATION_WINDOW))
            .stream()
            .map(ApiAuditLogView::from)
            .toList();

        return new AdminOrderDetail(order.getId(), order.getOrderNo(), order.getUserId(), order.getTotalAmount(),
            order.getStatus().name(), order.getPaymentDueAt(), order.getCreatedAt(), items, purchaseRequest,
            statusHistory, relatedApiLogs);
    }

    private static PurchaseRequestView toPurchaseRequestView(PurchaseRequest request) {
        return new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId());
    }
}
