package com.flashsale.order.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.order.application.CreatePurchaseRequestService;
import com.flashsale.order.application.PurchaseRequestRepository;
import com.flashsale.order.application.dto.PurchaseRequestView;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class PurchaseController {

    private final CreatePurchaseRequestService createPurchaseRequestService;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public PurchaseController(CreatePurchaseRequestService createPurchaseRequestService,
                               PurchaseRequestRepository purchaseRequestRepository) {
        this.createPurchaseRequestService = createPurchaseRequestService;
        this.purchaseRequestRepository = purchaseRequestRepository;
    }

    @PostMapping("/api/flash-sales/{id}/purchase-requests")
    public ResponseEntity<PurchaseRequestView> purchase(@PathVariable("id") Long flashSaleId,
                                                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                          @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        PurchaseRequest request = createPurchaseRequestService.createPurchaseRequest(userId, flashSaleId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId()));
    }

    @GetMapping("/api/purchase-requests/{requestId}")
    public PurchaseRequestView getStatus(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        PurchaseRequest request = purchaseRequestRepository.findByRequestId(requestId)
            .filter(r -> r.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("PURCHASE_REQUEST_NOT_FOUND", "Purchase request " + requestId + " does not exist"));
        return new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId());
    }
}
