package com.flashsale.admin.application.dto;

import com.flashsale.order.application.dto.PurchaseRequestView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code relatedApiLogs} is a deliberately manual cross-reference, not an automatic join: the
 * {@code orders} table stores no {@code trace_id} (nothing in this plan adds that column), so
 * there is no key to join {@code api_audit_logs} against directly. Instead it holds every
 * {@code api_audit_logs} row for this order's {@code userId} that falls within a tight time
 * window around the order's {@code PENDING_PAYMENT} status-history timestamp (falling back to
 * {@code createdAt} if that history row is somehow missing) — see
 * {@code AdminOrderQueryService.CORRELATION_WINDOW}. It is a "logs around this time" hint for the
 * admin UI to jump off from (e.g. a manual {@code /admin/api-logs?traceId=...} lookup), not a
 * guaranteed-exact correlation.
 */
public record AdminOrderDetail(
    Long id,
    String orderNo,
    Long userId,
    BigDecimal totalAmount,
    String status,
    Instant paymentDueAt,
    Instant createdAt,
    List<AdminOrderItemView> items,
    PurchaseRequestView purchaseRequest,
    List<OrderStatusHistoryView> statusHistory,
    List<ApiAuditLogView> relatedApiLogs
) {}
