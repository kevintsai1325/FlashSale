package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.common.client.OrderServiceClient;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminFlashSaleServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock ProductRepository productRepository;
    @Mock OrderServiceClient orderServiceClient;

    AdminFlashSaleService service;

    @Test
    void createWritesFlashSaleAndInventoryTogether() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        when(productRepository.findById(1L)).thenReturn(Optional.of(Product.create("Sneakers", "desc")));
        when(flashSaleRepository.save(any(FlashSale.class))).thenAnswer(invocation -> {
            FlashSale sale = invocation.getArgument(0);
            return sale;
        });

        Instant starts = Instant.now().plusSeconds(3600);
        Instant ends = Instant.now().plusSeconds(7200);
        service.create(1L, new BigDecimal("9.99"), starts, ends, 1, 50);

        verify(flashSaleRepository).save(any(FlashSale.class));
        // 庫存在另一個服務：建立活動因此是一個跨服務的寫入，順序是「遠端先成功、本地再提交」
        // （見 OrderServiceClient.declareInventory 的說明）。
        verify(orderServiceClient).declareInventory(any(), eq(50));
    }

    @Test
    void createThrowsNotFoundForUnknownProduct() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1, 50))
            .isInstanceOf(NotFoundException.class);
    }

    private void stubInventory(int totalQuantity) {
        when(orderServiceClient.inventories(java.util.List.of(1L)))
            .thenReturn(java.util.Map.of(1L, new OrderServiceClient.InventoryView(totalQuantity, totalQuantity, 0, 0)));
    }

    @Test
    void updateFreelyChangesEveryFieldWhileStillScheduled() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        stubInventory(50);

        Instant newStarts = Instant.now().plusSeconds(1800);
        Instant newEnds = Instant.now().plusSeconds(9000);
        service.update(1L, new BigDecimal("19.99"), newStarts, newEnds, 2, 80);

        assertThat(sale.getSalePrice()).isEqualByComparingTo("19.99");
        assertThat(sale.getStartsAt()).isEqualTo(newStarts);
        assertThat(sale.getEndsAt()).isEqualTo(newEnds);
        assertThat(sale.getPurchaseLimitPerUser()).isEqualTo(2);
        verify(orderServiceClient).declareInventory(1L, 80);
    }

    @Test
    void updateRejectsPriceChangeOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        Instant starts = Instant.now().minusSeconds(60);
        Instant ends = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, ends, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        stubInventory(50);

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("19.99"), starts, ends, 1, 50))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("已經開始");
    }

    @Test
    void updateRejectsTotalQuantityChangeOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        Instant starts = Instant.now().minusSeconds(60);
        Instant ends = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, ends, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        stubInventory(50);

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("9.99"), starts, ends, 1, 99))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("已經開始");
    }

    @Test
    void updateAllowsShorteningEndsAtOnceTheSaleHasStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        stubInventory(50);

        Instant earlierEnds = Instant.now().plusSeconds(60);
        service.update(1L, new BigDecimal("9.99"), starts, earlierEnds, 1, /* matches mocked inventory's current totalQuantity */ 50);

        assertThat(sale.getEndsAt()).isEqualTo(earlierEnds);
    }

    @Test
    void updateRejectsExtendingEndsAtPastTheOriginalOnceStarted() {
        service = new AdminFlashSaleService(flashSaleRepository, productRepository, orderServiceClient);
        Instant starts = Instant.now().minusSeconds(60);
        Instant originalEnds = Instant.now().plusSeconds(3600);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"), starts, originalEnds, 1);
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        stubInventory(50);

        Instant laterEnds = originalEnds.plusSeconds(3600);

        assertThatThrownBy(() -> service.update(1L, new BigDecimal("9.99"), starts, laterEnds, 1, 50))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("必須介於");
    }
}
