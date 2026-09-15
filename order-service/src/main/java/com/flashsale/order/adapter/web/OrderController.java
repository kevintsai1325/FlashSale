package com.flashsale.order.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.CancelOrderService;
import com.flashsale.order.application.OrderRepository;
import com.flashsale.order.application.dto.OrderDetail;
import com.flashsale.order.application.dto.OrderDtoMapper;
import com.flashsale.order.application.dto.OrderSummary;
import com.flashsale.order.domain.Order;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderRepository orderRepository;
    private final CancelOrderService cancelOrderService;

    public OrderController(OrderRepository orderRepository, CancelOrderService cancelOrderService) {
        this.orderRepository = orderRepository;
        this.cancelOrderService = cancelOrderService;
    }

    @GetMapping("/me")
    public List<OrderSummary> myOrders(@AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        return orderRepository.findAllByUserId(userId).stream()
            .map(OrderDtoMapper::toSummary)
            .toList();
    }

    @GetMapping("/{orderId}")
    public OrderDetail detail(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = orderRepository.findById(orderId)
            .filter(o -> o.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("ORDER_NOT_FOUND", "訂單 " + orderId + " 不存在"));
        return OrderDtoMapper.toDetail(order);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderDetail cancel(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = cancelOrderService.cancel(orderId, userId);
        return OrderDtoMapper.toDetail(order);
    }
}
