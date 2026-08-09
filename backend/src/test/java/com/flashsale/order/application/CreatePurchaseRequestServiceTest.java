package com.flashsale.order.application;

import com.flashsale.common.exception.ConflictException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.inventory.application.InventoryRepository;
import com.flashsale.inventory.domain.Inventory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreatePurchaseRequestServiceTest {

    @Mock FlashSaleRepository flashSaleRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock OrderRepository orderRepository;
    @Mock PurchaseRequestRepository purchaseRequestRepository;

    CreatePurchaseRequestService service;

    private FlashSale activeSale() {
        return FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 1);
    }

    @Test
    void succeedsWhenStockAvailable() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-1"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryRepository.findByFlashSaleIdForUpdate(10L))
            .thenReturn(Optional.of(Inventory.initialize(10L, 5)));
        when(orderRepository.save(any())).thenAnswer(inv -> {
            var order = inv.getArgument(0, com.flashsale.order.domain.Order.class);
            // Simulate what a real JPA repository does on save: assign a generated id.
            ReflectionTestUtils.setField(order, "id", 999L);
            return order;
        });
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-1");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SUCCEEDED);
        assertThat(result.getOrderId()).isNotNull();
        verify(inventoryRepository).save(argThat(inv -> inv.getAvailableQuantity() == 4));
    }

    @Test
    void marksSoldOutWhenNoStock() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-2"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(false);
        when(inventoryRepository.findByFlashSaleIdForUpdate(10L))
            .thenReturn(Optional.of(Inventory.initialize(10L, 0)));
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-2");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.SOLD_OUT);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void repeatingSameIdempotencyKeyReturnsSameResult() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        PurchaseRequest existing = PurchaseRequest.succeed(1L, 10L, "idem-3", 999L);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-3"))
            .thenReturn(Optional.of(existing));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-3");

        assertThat(result).isSameAs(existing);
        verifyNoInteractions(inventoryRepository, orderRepository);
    }

    @Test
    void rejectsWhenUserAlreadyHasSuccessfulOrder() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-4"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(activeSale()));
        when(purchaseRequestRepository.existsSucceededForUserAndFlashSale(1L, 10L)).thenReturn(true);
        when(purchaseRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PurchaseRequest result = service.createPurchaseRequest(1L, 10L, "idem-4");

        assertThat(result.getStatus()).isEqualTo(PurchaseRequestStatus.REJECTED);
    }

    @Test
    void rejectsWhenFlashSaleNotActive() {
        service = new CreatePurchaseRequestService(flashSaleRepository, inventoryRepository, orderRepository, purchaseRequestRepository);
        FlashSale ended = FlashSale.schedule(1L, new BigDecimal("9.99"),
            Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600), 1);
        when(purchaseRequestRepository.findByUserIdAndFlashSaleIdAndIdempotencyKey(1L, 10L, "idem-5"))
            .thenReturn(Optional.empty());
        when(flashSaleRepository.findById(10L)).thenReturn(Optional.of(ended));

        assertThatThrownBy(() -> service.createPurchaseRequest(1L, 10L, "idem-5"))
            .isInstanceOf(ConflictException.class);
    }
}
