package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.ProductView;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminProductServiceTest {

    @Mock ProductRepository productRepository;

    AdminProductService service;

    @Test
    void createSavesAndReturnsTheNewProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductView result = service.create("Limited Sneakers", "Only 100 pairs");

        assertThat(result.name()).isEqualTo("Limited Sneakers");
        assertThat(result.description()).isEqualTo("Only 100 pairs");
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void updateRenamesAnExistingProduct() {
        service = new AdminProductService(productRepository);
        Product existing = Product.create("Old Name", "Old description");
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        ProductView result = service.update(1L, "New Name", "New description");

        assertThat(result.name()).isEqualTo("New Name");
        assertThat(result.description()).isEqualTo("New description");
    }

    @Test
    void updateThrowsNotFoundForUnknownProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, "x", "y")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void listAllReturnsEveryProduct() {
        service = new AdminProductService(productRepository);
        when(productRepository.findAll()).thenReturn(List.of(
            Product.create("A", "a"), Product.create("B", "b")));

        List<ProductView> result = service.listAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("A");
        assertThat(result.get(1).name()).isEqualTo("B");
    }
}
