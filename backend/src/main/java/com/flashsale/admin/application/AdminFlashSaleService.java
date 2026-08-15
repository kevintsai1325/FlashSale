package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class AdminFlashSaleService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public AdminFlashSaleService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository,
                                  InventoryRepository inventoryRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional
    public FlashSale create(Long productId, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        productRepository.findById(productId)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + productId + " does not exist"));

        FlashSale sale = FlashSale.schedule(productId, salePrice, startsAt, endsAt, purchaseLimitPerUser);
        FlashSale saved = flashSaleRepository.save(sale);
        inventoryRepository.save(Inventory.initialize(saved.getId(), totalQuantity));
        return saved;
    }

    @Transactional
    public FlashSale update(Long id, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + id + " does not exist"));
        Instant now = Instant.now();

        Inventory inventory = inventoryRepository.findByFlashSaleId(id)
            .orElseThrow(() -> new NotFoundException("INVENTORY_NOT_FOUND", "Inventory for flash sale " + id + " does not exist"));

        if (sale.effectiveStatus(now) == FlashSaleStatus.SCHEDULED) {
            sale.reschedule(salePrice, startsAt, endsAt, purchaseLimitPerUser);
            // A SCHEDULED sale has 0 reserved/sold quantity by construction (nothing can
            // purchase it yet), so it's safe to just reset the existing inventory row to
            // the new totalQuantity rather than compute a partial adjustment.
            inventory.resetTo(totalQuantity);
            return sale;
        }

        boolean otherFieldsChanged = salePrice.compareTo(sale.getSalePrice()) != 0
            || !startsAt.equals(sale.getStartsAt())
            || purchaseLimitPerUser != sale.getPurchaseLimitPerUser()
            || totalQuantity != inventory.getTotalQuantity();
        if (otherFieldsChanged) {
            throw new ConflictException("FLASH_SALE_ALREADY_STARTED",
                "Flash sale " + id + " has already started — only endsAt may be shortened");
        }
        if (endsAt.isBefore(now) || endsAt.isAfter(sale.getEndsAt())) {
            throw new ConflictException("FLASH_SALE_ENDS_AT_OUT_OF_RANGE",
                "endsAt must be between now and the flash sale's current endsAt");
        }
        sale.endEarly(endsAt);
        return sale;
    }
}
