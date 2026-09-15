package com.flashsale.order.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.OrderStatusHistoryRepository;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.OrderStatus;
import com.flashsale.order.domain.OrderStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 後台的訂單清單與詳情。**這是 BFF 的做法**：後台的端點仍然在 platform（它要合併稽核紀錄、
 * 檢查 ADMIN 角色、對前端維持同一個網址），但訂單資料由擁有者回答。
 *
 * 刻意不回傳「相關的 API 稽核紀錄」：那張表在 platform，由呼叫端自己併上去。
 * 讓這裡去查別人的稽核表，等於把剛拆掉的耦合原封不動搬過來。
 */
@RestController
public class InternalOrderController {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    public InternalOrderController(OrderRepository orderRepository,
                                    OrderStatusHistoryRepository orderStatusHistoryRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
    }

    @GetMapping("/internal/orders")
    public PagedOrders list(@RequestParam(name = "status", required = false) String status,
                             @RequestParam(name = "page", defaultValue = "0") int page,
                             @RequestParam(name = "size", defaultValue = "20") int size) {
        OrderStatus orderStatus = status == null || status.isBlank() ? null : OrderStatus.valueOf(status);
        Page<Order> result = orderRepository.findAllPaged(orderStatus,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<OrderSummary> content = result.getContent().stream()
            .map(o -> new OrderSummary(o.getId(), o.getOrderNo(), o.getUserId(), o.getTotalAmount(),
                o.getStatus().name(), o.getCreatedAt()))
            .toList();
        return new PagedOrders(content, result.getTotalElements(), page, size);
    }

    @GetMapping("/internal/orders/{id}")
    public OrderDetail detail(@PathVariable("id") Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "訂單 " + orderId + " 不存在"));
        List<OrderItemView> items = order.getItems().stream()
            .map(i -> new OrderItemView(i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
            .toList();
        List<StatusHistoryView> history = orderStatusHistoryRepository.findByOrderId(orderId).stream()
            .map(h -> new StatusHistoryView(h.getFromStatus() == null ? null : h.getFromStatus().name(),
                h.getToStatus().name(), h.getChangedAt()))
            .toList();
        return new OrderDetail(order.getId(), order.getOrderNo(), order.getUserId(), order.getTotalAmount(),
            order.getStatus().name(), order.getPaymentDueAt(), order.getCreatedAt(),
            order.getFlashSaleId(), order.getPurchaseRequestId(), items, history);
    }

    public record OrderSummary(Long id, String orderNo, Long userId, BigDecimal totalAmount,
                                String status, Instant createdAt) {}

    public record PagedOrders(List<OrderSummary> content, long totalElements, int page, int size) {}

    public record OrderItemView(Long productId, String productName, int quantity, BigDecimal unitPrice) {}

    public record StatusHistoryView(String fromStatus, String toStatus, Instant changedAt) {}

    public record OrderDetail(Long id, String orderNo, Long userId, BigDecimal totalAmount, String status,
                               Instant paymentDueAt, Instant createdAt, Long flashSaleId, UUID purchaseRequestId,
                               List<OrderItemView> items, List<StatusHistoryView> statusHistory) {}
}
