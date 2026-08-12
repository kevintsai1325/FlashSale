package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import com.flashsale.order.domain.Order;
import com.flashsale.order.domain.PurchaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class CreatePurchaseRequestService {

    private final FlashSaleRepository flashSaleRepository;
    private final InventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;

    public CreatePurchaseRequestService(FlashSaleRepository flashSaleRepository, InventoryRepository inventoryRepository,
                                         OrderRepository orderRepository, PurchaseRequestRepository purchaseRequestRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
    }

    @Transactional
    public PurchaseRequest createPurchaseRequest(Long userId, Long flashSaleId, String idempotencyKey) {
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

        if (purchaseRequestRepository.existsSucceededForUserAndFlashSale(userId, flashSaleId)) {
            return purchaseRequestRepository.save(PurchaseRequest.reject(userId, flashSaleId, idempotencyKey));
        }

        Inventory inventory = inventoryRepository.findByFlashSaleIdForUpdate(flashSaleId)
            .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND", "Inventory for flash sale " + flashSaleId + " does not exist"));

        int quantity = flashSale.getPurchaseLimitPerUser();
        if (!inventory.hasStock(quantity)) {
            return purchaseRequestRepository.save(PurchaseRequest.soldOut(userId, flashSaleId, idempotencyKey));
        }

        inventory.sell(quantity);
        inventoryRepository.save(inventory);

        Order order = Order.createPendingPayment(userId, flashSale.getProductId(), quantity, flashSale.getSalePrice());
        Order savedOrder = orderRepository.save(order);

        return purchaseRequestRepository.save(
            PurchaseRequest.succeed(userId, flashSaleId, idempotencyKey, savedOrder.getId()));
    }
}
