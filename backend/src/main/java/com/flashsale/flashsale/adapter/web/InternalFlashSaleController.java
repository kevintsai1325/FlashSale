package com.flashsale.flashsale.adapter.web;

import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 給 purchase-service 用的內部 API。刻意不走 /api/ 前綴，Nginx 對 /internal/ 一律回 404，
 * 所以它只在叢集內部可達。
 *
 * 回傳 startsAt / endsAt 而不是一個算好的「可否購買」布林值：呼叫端會快取這個回應，
 * 布林值被快取就會把活動的起訖邊界一起模糊掉，時間戳則不會因為快取而改變意義。
 *
 * 這個回應的形狀是兩個服務之間的契約，對應 purchase-service 的 InternalFlashSaleResponse。
 */
@RestController
public class InternalFlashSaleController {

    private final FlashSaleRepository flashSaleRepository;

    public InternalFlashSaleController(FlashSaleRepository flashSaleRepository) {
        this.flashSaleRepository = flashSaleRepository;
    }

    @GetMapping("/internal/flash-sales/{id}")
    public InternalFlashSaleResponse get(@PathVariable("id") Long flashSaleId) {
        FlashSale flashSale = flashSaleRepository.findById(flashSaleId)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + flashSaleId + " 不存在"));
        return new InternalFlashSaleResponse(flashSale.getId(), flashSale.getProductId(), flashSale.getSalePrice(),
            flashSale.getStartsAt(), flashSale.getEndsAt(), flashSale.getPurchaseLimitPerUser());
    }

    public record InternalFlashSaleResponse(Long id, Long productId, BigDecimal salePrice,
                                             Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {}
}
