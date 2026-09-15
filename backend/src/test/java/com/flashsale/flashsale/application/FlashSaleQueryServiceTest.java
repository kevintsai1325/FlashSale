package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import com.flashsale.common.client.OrderServiceClient;
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
    @Mock OrderServiceClient orderServiceClient;

    FlashSaleQueryService service;

    @Test
    void listActiveSalesJoinsProductDetails() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
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
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        when(flashSaleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void detailIncludesInventoryQuantities() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderServiceClient.inventories(List.of(1L)))
            .thenReturn(java.util.Map.of(1L, new OrderServiceClient.InventoryView(100, 42, 0, 58)));

        var result = service.getDetail(1L);

        assertThat(result.totalQuantity()).isEqualTo(100);
        assertThat(result.availableQuantity()).isEqualTo(42);
    }

    @Test
    void detailDefaultsQuantitiesToZeroWhenNoInventoryRow() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        // 庫存查不到（order-service 沒有那一列，或那次呼叫降級了）—— 顯示 0，不讓整頁失敗。
        when(orderServiceClient.inventories(List.of(1L))).thenReturn(java.util.Map.of());

        var result = service.getDetail(1L);

        assertThat(result.totalQuantity()).isEqualTo(0);
        assertThat(result.availableQuantity()).isEqualTo(0);
    }

    // FlashSale.schedule() always persists FlashSaleStatus.SCHEDULED and nothing in the codebase
    // ever transitions it afterwards — the status reported to callers must instead be computed
    // from startsAt/endsAt/now, or a sale whose window has already elapsed keeps reporting itself
    // as ACTIVE forever (the bug: list page shows "搶購中" for an activity that ended days ago,
    // then the purchase attempt is correctly rejected by CreatePurchaseRequestService's real
    // isPurchasableAt() check, producing a confusing contradiction for the user).

    @Test
    void listReportsEndedForASaleWhoseWindowHasAlreadyElapsed() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findAll()).thenReturn(List.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        List<FlashSaleSummary> result = service.listAll();

        assertThat(result.get(0).status()).isEqualTo("ENDED");
    }

    @Test
    void listReportsScheduledForASaleThatHasNotStartedYet() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findAll()).thenReturn(List.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        List<FlashSaleSummary> result = service.listAll();

        assertThat(result.get(0).status()).isEqualTo("SCHEDULED");
    }

    @Test
    void detailReportsEndedForASaleWhoseWindowHasAlreadyElapsed() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository, orderServiceClient);
        FlashSale sale = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 1);
        Product product = Product.create("Limited Sneakers", "Only 100 pairs");
        when(flashSaleRepository.findById(1L)).thenReturn(Optional.of(sale));
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderServiceClient.inventories(List.of(1L))).thenReturn(java.util.Map.of());

        var result = service.getDetail(1L);

        assertThat(result.status()).isEqualTo("ENDED");
    }
}
