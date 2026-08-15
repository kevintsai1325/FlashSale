package com.flashsale.admin.adapter.web;

import com.flashsale.admin.adapter.web.dto.FlashSaleCreateRequest;
import com.flashsale.admin.adapter.web.dto.FlashSaleUpdateRequest;
import com.flashsale.admin.application.AdminFlashSaleService;
import com.flashsale.flashsale.application.FlashSaleQueryService;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only flash-sale management behind {@code /api/admin/**} (guarded by
 * {@code SecurityConfig}'s {@code hasRole("ADMIN")} rule). {@code GET} reuses the existing
 * {@link FlashSaleQueryService#listAll()} (design spec §4) rather than a new admin-specific read
 * path — {@link FlashSaleSummary} already has everything this list view needs.
 */
@RestController
@RequestMapping("/api/admin/flash-sales")
public class AdminFlashSaleController {

    private final AdminFlashSaleService adminFlashSaleService;
    private final FlashSaleQueryService flashSaleQueryService;

    public AdminFlashSaleController(AdminFlashSaleService adminFlashSaleService,
                                     FlashSaleQueryService flashSaleQueryService) {
        this.adminFlashSaleService = adminFlashSaleService;
        this.flashSaleQueryService = flashSaleQueryService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody FlashSaleCreateRequest request) {
        FlashSale created = adminFlashSaleService.create(request.productId(), request.salePrice(),
            request.startsAt(), request.endsAt(), request.purchaseLimitPerUser(), request.totalQuantity());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", created.getId()));
    }

    @GetMapping
    public List<FlashSaleSummary> list() {
        return flashSaleQueryService.listAll();
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @Valid @RequestBody FlashSaleUpdateRequest request) {
        FlashSale updated = adminFlashSaleService.update(id, request.salePrice(), request.startsAt(),
            request.endsAt(), request.purchaseLimitPerUser(), request.totalQuantity());
        return Map.of("id", updated.getId(), "status", updated.effectiveStatus(Instant.now()).name());
    }
}
