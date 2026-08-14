package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashSaleQueryServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;

    FlashSaleQueryService service;

    @Test
    void listActiveSalesJoinsProductDetails() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, inventoryRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findAll()).thenReturn(List.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        List<FlashSaleSummary> result = service.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).productName()).isEqualTo("Limited Sneakers");
        assertThat(result.get(0).salePrice()).isEqualByComparingTo("9.99");
    }

    @Test
    void detailThrowsNotFoundForUnknownSale() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, inventoryRepository);
        when(flashSaleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailIncludesInventoryQuantities() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, inventoryRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        Inventory inventory = Inventory.initialize(1L, 100);
        inventory.sell(58);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(inventory));

        var result = service.getDetail(1L);

        assertThat(result.totalQuantity()).isEqualTo(100);
        assertThat(result.availableQuantity()).isEqualTo(42);
    }

    @Test
    void detailDefaultsQuantitiesToZeroWhenNoInventoryRow() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, inventoryRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.empty());

        var result = service.getDetail(1L);

        assertThat(result.totalQuantity()).isEqualTo(0);
        assertThat(result.availableQuantity()).isEqualTo(0);
    }
}
