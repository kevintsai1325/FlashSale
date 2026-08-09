package com.flashsale.flashsale.adapter.web;

import com.flashsale.flashsale.application.FlashSaleQueryService;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flash-sales")
public class FlashSaleController {

    private final FlashSaleQueryService queryService;

    public FlashSaleController(FlashSaleQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public List<FlashSaleSummary> list() {
        return queryService.listAll();
    }

    @GetMapping("/{id}")
    public FlashSaleDetail detail(@PathVariable Long id) {
        return queryService.getDetail(id);
    }
}
