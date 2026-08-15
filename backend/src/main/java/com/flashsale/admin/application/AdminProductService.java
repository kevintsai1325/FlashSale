package com.flashsale.admin.application;

import com.flashsale.admin.application.dto.ProductView;
import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AdminProductService {

    private final ProductRepository productRepository;

    public AdminProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductView create(String name, String description) {
        Product product = Product.create(name, description);
        Product saved = productRepository.save(product);
        return toView(saved);
    }

    @Transactional
    public ProductView update(Long id, String name, String description) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "Product " + id + " does not exist"));
        product.rename(name, description);
        return toView(product);
    }

    public List<ProductView> listAll() {
        return productRepository.findAll().stream().map(this::toView).toList();
    }

    private ProductView toView(Product product) {
        return new ProductView(product.getId(), product.getName(), product.getDescription());
    }
}
