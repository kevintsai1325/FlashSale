package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class FlashSaleQueryService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;

    public FlashSaleQueryService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository,
                                  InventoryRepository inventoryRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
    }

    public List<FlashSaleSummary> listAll() {
        return flashSaleRepository.findAll().stream()
            .map(sale -> {
                Product product = productFor(sale);
                int totalQuantity = inventoryRepository.findByFlashSaleId(sale.getId())
                    .map(Inventory::getTotalQuantity).orElse(0);
                return new FlashSaleSummary(sale.getId(), sale.getProductId(), product.getName(), sale.getSalePrice(),
                    sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(), totalQuantity,
                    sale.effectiveStatus(Instant.now()).name());
            })
            .toList();
    }

    public FlashSaleDetail getDetail(Long id) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + id + " 不存在"));
        Product product = productFor(sale);
        Inventory inventory = inventoryRepository.findByFlashSaleId(id).orElse(null);
        int totalQuantity = inventory != null ? inventory.getTotalQuantity() : 0;
        int availableQuantity = inventory != null ? inventory.getAvailableQuantity() : 0;
        return new FlashSaleDetail(sale.getId(), product.getName(), product.getDescription(), sale.getSalePrice(),
            sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(),
            totalQuantity, availableQuantity, sale.effectiveStatus(Instant.now()).name());
    }

    private Product productFor(FlashSale sale) {
        return productRepository.findById(sale.getProductId())
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "商品 " + sale.getProductId() + " 不存在"));
    }
}
