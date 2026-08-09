package com.flashsale.catalog.application;

import com.flashsale.catalog.domain.Product;
import java.util.Optional;

public interface ProductRepository {
    Optional<Product> findById(Long id);
}
