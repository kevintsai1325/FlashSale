package com.flashsale.flashsale.adapter.web;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
    private final ProductRepository productRepository;

    public InternalFlashSaleController(FlashSaleRepository flashSaleRepository, ProductRepository productRepository) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
    }

    @GetMapping("/internal/flash-sales/{id}")
    public InternalFlashSaleResponse get(@PathVariable("id") Long flashSaleId) {
        FlashSale flashSale = flashSaleRepository.findById(flashSaleId)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + flashSaleId + " 不存在"));
        // productName 一起回傳（P5）：order-service 不再有 catalog 模組，建單時的商品名稱
        // 必須由事件帶過去，而事件的來源是這個回應。多回一個欄位，換掉一次跨服務查詢。
        String productName = productRepository.findById(flashSale.getProductId())
            .map(Product::getName)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "商品 " + flashSale.getProductId() + " 不存在"));
        return new InternalFlashSaleResponse(flashSale.getId(), flashSale.getProductId(), productName,
            flashSale.getSalePrice(), flashSale.getStartsAt(), flashSale.getEndsAt(),
            flashSale.getPurchaseLimitPerUser());
    }

    /**
     * 目前可購買的活動 id。order-service 的庫存對帳排程需要它 ——
     * 那個排程要掃「進行中的活動」，而活動資料的所有權在這裡。
     *
     * 只回 id：對帳要的是「哪些活動」，其餘欄位它自己的 inventory 表都有。
     * 回傳完整物件只會讓這個端點變成另一個要維護的投影。
     */
    @GetMapping("/internal/flash-sales/active")
    public List<Long> activeFlashSaleIds() {
        Instant now = Instant.now();
        return flashSaleRepository.findAll().stream()
            .filter(sale -> sale.isPurchasableAt(now))
            .map(FlashSale::getId)
            .toList();
    }

    public record InternalFlashSaleResponse(Long id, Long productId, String productName, BigDecimal salePrice,
                                             Instant startsAt, Instant endsAt, int purchaseLimitPerUser) {}
}
