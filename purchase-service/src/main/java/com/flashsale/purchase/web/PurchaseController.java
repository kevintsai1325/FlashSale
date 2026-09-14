package com.flashsale.purchase.web;

import com.flashsale.purchase.application.CreatePurchaseRequestService;
import com.flashsale.purchase.application.PurchaseRequestRepository;
import com.flashsale.purchase.application.dto.PurchaseRequestView;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.exception.NotFoundException;
import com.flashsale.purchase.web.dto.PurchaseCreateRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 路徑與回應形狀與拆分前完全相同 —— 前端不知道也不需要知道這兩個端點換了一個行程在服務。
 */
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
                                                          @Valid @RequestBody PurchaseCreateRequest body,
                                                          @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        PurchaseRequest request = createPurchaseRequestService.createPurchaseRequest(userId, flashSaleId, idempotencyKey, body.quantity());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId()));
    }

    @GetMapping("/api/purchase-requests/{requestId}")
    public PurchaseRequestView getStatus(@PathVariable UUID requestId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = jwt.getClaim("userId");
        PurchaseRequest request = purchaseRequestRepository.findByRequestId(requestId)
            .filter(r -> r.getUserId().equals(userId))
            .orElseThrow(() -> new NotFoundException("PURCHASE_REQUEST_NOT_FOUND", "搶購請求 " + requestId + " 不存在"));
        return new PurchaseRequestView(request.getRequestId(), request.getStatus().name(), request.getOrderId());
    }
}
