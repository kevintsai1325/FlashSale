package com.flashsale.payment.adapter.web;

import com.flashsale.order.application.dto.OrderDetail;
import com.flashsale.order.application.dto.OrderDtoMapper;
import com.flashsale.order.domain.Order;
import com.flashsale.payment.adapter.web.dto.SubmitPaymentRequest;
import com.flashsale.payment.application.SubmitPaymentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class PaymentController {

    private final SubmitPaymentService submitPaymentService;

    public PaymentController(SubmitPaymentService submitPaymentService) {
        this.submitPaymentService = submitPaymentService;
    }

    @PostMapping("/{orderId}/payments")
    public OrderDetail submit(@PathVariable Long orderId, @RequestBody SubmitPaymentRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        Order order = submitPaymentService.submit(orderId, userId, request.result());
        return OrderDtoMapper.toDetail(order);
    }
}
