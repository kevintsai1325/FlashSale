package com.flashsale.catalog.adapter.persistence;

import com.flashsale.catalog.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductJpaRepository extends JpaRepository<Product, Long> {}
