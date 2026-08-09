package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FlashSaleQueryService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;

    public FlashSaleQueryService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
    }

    public List<FlashSaleSummary> listAll() {
        return flashSaleRepository.findAll().stream()
            .map(sale -> {
                Product product = productFor(sale);
                return new FlashSaleSummary(sale.getId(), product.getName(), sale.getSalePrice(),
                    sale.getStartsAt(), sale.getEndsAt(), sale.getStatus().name());
            })
            .toList();
    }

    public FlashSaleDetail getDetail(Long id) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "Flash sale " + id + " does not exist"));
        Product product = productFor(sale);
        return new FlashSaleDetail(sale.getId(), product.getName(), product.getDescription(), sale.getSalePrice(),
            sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(), sale.getStatus().name());
    }

    private Product productFor(FlashSale sale) {
        return productRepository.findById(sale.getProductId())
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + sale.getProductId() + " does not exist"));
    }
}
