package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminFlashSaleServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;

    AdminFlashSaleService service;

    @Test
    void createWritesFlashSaleAndInventoryTogether() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.create("Sneakers", "desc")));
        when(flashSaleRepository.save(any(FlashSale.class))).thenAnswer(invocation -> {
            FlashSale sale = invocation.getArgument(0);
            return sale;
        });

        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = Instant.now().plusSeconds(7200);
        service.create(1L, new BigDecimal("9.99"), starts, ends, 1, 50);

        verify(flashSaleRepository).save(any(FlashSale.class));
        verify(inventoryRepository).save(any(Inventory.class));
    }

    @Test
    void createThrowsNotFoundForUnknownProduct() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1, 50))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateFreelyChangesEveryFieldWhileStillScheduled() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        Inventory inventory = Inventory.initialize(1L, 50);
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(inventory));

        Instant newStarts = Instant.now().plusSeconds(1800);
        Instant newEnds = Instant.now().plusSeconds(9000);
        service.update(1L, new BigDecimal("19.99"), newStarts, newEnds, 2, 80);

        assertThat(sale.getSalePrice()).isEqualByComparingTo("19.99");
        assertThat(sale.getStartsAt()).isEqualTo(newStarts);
        assertThat(sale.getEndsAt()).isEqualTo(newEnds);
        assertThat(sale.getPurchaseLimitPerUser()).isEqualTo(2);
        assertThat(inventory.getTotalQuantity()).isEqualTo(80);
        assertThat(inventory.getAvailableQuantity()).isEqualTo(80);
    }

    @Test
    void updateRejectsPriceChangeOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant ends = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, ends, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(Inventory.initialize(1L, 50)));

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("19.99"), starts, ends, 1, 50))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("已經開始");
    }

    @Test
    void updateRejectsTotalQuantityChangeOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant ends = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, ends, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(Inventory.initialize(1L, 50)));

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("9.99"), starts, ends, 1, 99))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("已經開始");
    }

    @Test
    void updateAllowsShorteningEndsAtOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(Inventory.initialize(1L, 50)));

        Instant earlierEnds = Instant.now().plusSeconds(60);
        service.update(1L, new BigDecimal("9.99"), starts, earlierEnds, 1, /* matches mocked inventory's current totalQuantity */ 50);

        assertThat(sale.getEndsAt()).isEqualTo(earlierEnds);
    }

    @Test
    void updateRejectsExtendingEndsAtPastTheOriginalOnceStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, inventoryRepository);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(inventoryRepository.findByFlashSaleId(1L)).thenReturn(Optional.of(Inventory.initialize(1L, 50)));

        Instant laterEnds = originalEnds.plusSeconds(3600);

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("9.99"), starts, laterEnds, 1, 50))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("必須介於");
    }
}
