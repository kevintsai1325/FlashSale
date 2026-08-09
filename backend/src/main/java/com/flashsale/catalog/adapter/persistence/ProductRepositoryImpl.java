package com.flashsale.catalog.adapter.persistence;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository jpaRepository;

    public ProductRepositoryImpl(ProductJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Product> findById(Long id) {
        return jpaRepository.findById(id);
    }
}
