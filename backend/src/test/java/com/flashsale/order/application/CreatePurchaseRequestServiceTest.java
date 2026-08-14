package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.messaging.OutboxWriter;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryStockGateway;
import com.flashsale.inventory.application.StockReservationResult;
import com.flashsale.order.domain.PurchaseRequest;
import com.flashsale.order.domain.PurchaseRequestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePurchaseRequestServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock InventoryStockGateway inventoryStockGateway;
    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock OutboxWriter outboxWriter;

    CreatePurchaseRequestService service;

    private FlashSale activeSale() {
        return FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
    }

    @Test
    void reservesStockAndWritesOutboxEventWhenStockAvailable() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-1"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.RESERVED);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> {
            PurchaseRequest saved = inv.getArgument(0);
            // Simulate what a real JPA repository does on save: assign a generated id.
            ReflectionTestUtils.setField(saved, "id", 999L);
            return saved;
        });

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-1");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
        verify(outboxWriter).write(eq("PurchaseRequest"), any(), eq("CreateOrderRequested"), any());
    }

    @Test
    void marksSoldOutWhenRedisReportsInsufficientStock() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-2"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryStockGateway.reserve(10L, 1)).thenReturn(StockReservationResult.INSUFFICIENT_STOCK);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-2");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SOLD_OUT);
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void repeatingSameIdempotencyKeyReturnsSameResultWithoutTouchingRedisOrOutbox() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        PurchaseRequest existing = PurchaseRequest.pending(1L, 10L, "idem-3");
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-3"))
            .thenReturn(Optional.of(existing));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-3");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void rejectsWhenUserAlreadyHasSuccessfulOrder() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-4"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(true);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-4");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }

    @Test
    void throwsConflictWhenFlashSaleNotActive() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryStockGateway, purchaseRequestRepository, outboxWriter);
        FlashSale notYetStarted = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), 1);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-5"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(notYetStarted));

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-5"))
            .isInstanceOf(ConflictException.class);
        verifyNoInteractions(inventoryStockGateway, outboxWriter);
    }
}
