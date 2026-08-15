package com.flashsale.admin.adapter.web;

import com.flashsale.admin.adapter.web.dto.ProductCreateRequest;
import com.flashsale.admin.adapter.web.dto.ProductUpdateRequest;
import com.flashsale.admin.application.AdminProductService;
import com.flashsale.admin.application.dto.ProductView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only product management behind {@code /api/admin/**} (guarded by {@code SecurityConfig}'s
 * {@code hasRole("ADMIN")} rule). No delete endpoint by design — see design spec §1.
 */
@RestController
@RequestMapping("/api/admin/products")
public class AdminProductController {

    private final AdminProductService adminProductService;

    public AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    @PostMapping
    public ResponseEntity<ProductView> create(@Valid @RequestBody ProductCreateRequest request) {
        ProductView created = adminProductService.create(request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<ProductView> list() {
        return adminProductService.listAll();
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        return adminProductService.update(id, request.name(), request.description());
    }
}
