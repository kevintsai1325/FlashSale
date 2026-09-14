package com.flashsale.purchase.application;

import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import com.flashsale.purchase.exception.ConflictException;
import com.flashsale.purchase.messaging.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 從 backend 搬過來的測試，斷言逐條保留 —— 拆分不該改變搶購的決策邏輯。
 * 唯一的差別是活動資料的來源從 FlashSaleRepository 換成 FlashSaleClient。
 */
@ExtendWith(MockitoExtension.class)
class CreatePurchaseRequestServiceTest {

    @Mock FlashSaleClient flashSaleClient;
    @Mock InventoryStockGateway inventoryStockGateway;
    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock OutboxWriter outboxWriter;

    CreatePurchaseRequestService service;

    private FlashSaleSnapshot activeSale() {
        return new FlashSaleSnapshot(10L, 1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
    }

    private CreatePurchaseRequestService service() {
        return new CreatePurchaseRequestService(flashSaleClient, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
    }

    private void stubSaveAssigningId() {
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> {
            PurchaseRequest saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 999L);
            return saved;
        });
    }

    @Test
    void reservesStockAndWritesOutboxEventWhenStockAvailable() {
        service = service();
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-1"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(activeSale());
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.RESERVED);
        stubSaveAssigningId();

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-1", 1);

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
        verify(outboxWriter).write(eq("PurchaseRequest"), any(), eq("CreateOrderRequested"), any());
    }

    @Test
    void reservesTheRequestedQuantityRatherThanAlwaysTheFullLimit() {
        service = service();
        FlashSaleSnapshot saleWithLimitOfFive = new FlashSaleSnapshot(10L, 1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 5);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-qty"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(saleWithLimitOfFive);
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 3)).thenReturn(StockReservationResult.RESERVED);
        stubSaveAssigningId();

        service.createPurchaseRequest(1L, 10L, "idem-qty", 3);

        verify(inventoryStockGateway).reserve(10L, 3);
    }

    @Test
    void throwsConflictWhenQuantityExceedsThePurchaseLimit() {
        service = service();
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-over"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(activeSale());

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-over", 2))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void marksSoldOutWhenRedisReportsInsufficientStock() {
        service = service();
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-2"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(activeSale());
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.INSUFFICIENT_STOCK);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-2", 1);

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SOLD_OUT);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void repeatingSameIdempotencyKeyReturnsSameResultWithoutTouchingRedisOrOutbox() {
        service = service();
        PurchaseRequest existing = PurchaseRequest.pending(1L, 10L, "idem-3");
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-3"))
            .thenReturn(Optional.of(existing));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-3", 1);

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(inventoryStockGateway, outboxWriter, flashSaleClient);
    }

    @Test
    void rejectsWhenUserAlreadyHasSuccessfulOrder() {
        service = service();
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-4"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(activeSale());
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(true);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-4", 1);

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void throwsConflictWhenFlashSaleNotActive() {
        service = service();
        FlashSaleSnapshot notYetStarted = new FlashSaleSnapshot(10L, 1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-5"))
            .thenReturn(Optional.empty());
        when(flashSaleClient.fetch(10L)).thenReturn(notYetStarted);

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-5", 1))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }
}
