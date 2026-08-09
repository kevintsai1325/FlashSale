package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
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

    FlashSaleQueryService service;

    @Test
    void listActiveSalesJoinsProductDetails() {
        service = new FlashSaleQueryService(flashSaleRepository, productRepository);
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
        service = new FlashSaleQueryService(flashSaleRepository, productRepository);
        when(flashSaleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(99L)).isInstanceOf(NotFoundException.class);
    }
}
