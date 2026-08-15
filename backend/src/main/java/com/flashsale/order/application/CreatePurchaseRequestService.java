package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.common.messaging.EventTypes;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.order.application.event.CreateOrderRequestedEvent;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CreatePurchaseRequestService {

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryStockGateway inventoryStockGateway;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OutboxWriter outboxWriter;

    public CreatePurchaseRequestService(FlashSaleRepository flashSaleRepository, InventoryStockGateway inventoryStockGateway,
                                         PurchaseRequestRepository purchaseRequestRepository, OutboxWriter outboxWriter) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryStockGateway = inventoryStockGateway;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public PurchaseRequest createPurchaseRequest(Long userId, Long flashSaleId, String idempotencyKey, int quantity) {
        var existing = purchaseRequestRepository
            .findByUserIdAndFlashSaleIdAndIdempotencyKey(userId, flashSaleId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        FlashSale flashSale = flashSaleRepository.findById(flashSaleId)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + flashSaleId + " does not exist"));

        if (!flashSale.isPurchasableAt(Instant.now())) {
            throw new ConflictException("FLASH_SALE_NOT_ACTIVE", "Flash sale is not currently active");
        }

        if (quantity > flashSale.getPurchaseLimitPerUser()) {
            throw new ConflictException("PURCHASE_QUANTITY_EXCEEDS_LIMIT",
                "Quantity " + quantity + " exceeds the purchase limit of " + flashSale.getPurchaseLimitPerUser() + " for this flash sale");
        }

        if (purchaseRequestRepository.existsSucceededForUserAndFlashSale(userId, flashSaleId)) {
            return purchaseRequestRepository.save(PurchaseRequest.reject(userId, flashSaleId, idempotencyKey));
        }

        StockReservationResult reservation = inventoryStockGateway.reserve(flashSaleId, quantity);
        if (reservation == StockReservationResult.INSUFFICIENT_STOCK) {
            return purchaseRequestRepository.save(PurchaseRequest.soldOut(userId, flashSaleId, idempotencyKey));
        }

        PurchaseRequest request = purchaseRequestRepository.save(PurchaseRequest.pending(userId, flashSaleId, idempotencyKey));

        outboxWriter.write("PurchaseRequest", request.getId().toString(), EventTypes.CREATE_ORDER_REQUESTED,
            new CreateOrderRequestedEvent(request.getId(), userId, flashSaleId, flashSale.getProductId(), quantity, flashSale.getSalePrice()));

        return request;
    }
}
