package com.flashsale.admin.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.common.exception.ConflictException;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.FlashSaleRepository;
import com.flashsale.flashsale.domain.FlashSale;
import com.flashsale.flashsale.domain.FlashSaleStatus;
import com.flashsale.common.client.OrderServiceClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 後台的活動 CRUD。P5 之後**建立一場活動是一個跨服務的寫入**：活動在這裡，庫存在
 * order-service。兩者沒有共同的交易，所以順序是刻意選的 —— 見
 * {@link OrderServiceClient#declareInventory}。
 *
 * 遠端呼叫發生在 {@code @Transactional} 之內，也就是本地交易會跨越一次網路往返。
 * 這在後台 CRUD（每天數次）是可以接受的；同樣的寫法放在搶購熱路徑上就不行。
 */
@Service
public class AdminFlashSaleService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;
    private final OrderServiceClient orderServiceClient;

    public AdminFlashSaleService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository,
                                  OrderServiceClient orderServiceClient) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
        this.orderServiceClient = orderServiceClient;
    }

    @Transactional
    public FlashSale create(Long productId, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        productRepository.findById(productId)
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "商品 " + productId + " 不存在"));

        FlashSale sale = FlashSale.schedule(productId, salePrice, startsAt, endsAt, purchaseLimitPerUser);
        FlashSale saved = flashSaleRepository.save(sale);
        // flush 是必要的：需要資料庫產生的 id 才能宣告庫存，而這個交易還沒提交。
        flashSaleRepository.flush();
        orderServiceClient.declareInventory(saved.getId(), totalQuantity);
        return saved;
    }

    @Transactional
    public FlashSale update(Long id, BigDecimal salePrice, Instant startsAt, Instant endsAt,
                             int purchaseLimitPerUser, int totalQuantity) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + id + " 不存在"));
        Instant now = Instant.now();

        OrderServiceClient.InventoryView inventory = orderServiceClient.inventories(java.util.List.of(id)).get(id);
        if (inventory == null) {
            throw new NotFoundException("INVENTORY_NOT_FOUND", "搶購活動 " + id + " 的庫存資料不存在");
        }

        if (sale.effectiveStatus(now) == FlashSaleStatus.SCHEDULED) {
            sale.reschedule(salePrice, startsAt, endsAt, purchaseLimitPerUser);
            // 還沒開始的活動 reserved/sold 必定為 0（沒有人買得到），所以直接重設總量即可。
            // order-service 那側會再檢查一次這個前提 —— 跨服務的驗證不能只放在呼叫端。
            orderServiceClient.declareInventory(id, totalQuantity);
            return sale;
        }

        boolean otherFieldsChanged = salePrice.compareTo(sale.getSalePrice()) != 0
            || !startsAt.equals(sale.getStartsAt())
            || purchaseLimitPerUser != sale.getPurchaseLimitPerUser()
            || totalQuantity != inventory.totalQuantity();
        if (otherFieldsChanged) {
            throw new ConflictException("FLASH_SALE_ALREADY_STARTED",
                "搶購活動 " + id + " 已經開始,只能縮短結束時間");
        }
        if (endsAt.isBefore(now) || endsAt.isAfter(sale.getEndsAt())) {
            throw new ConflictException("FLASH_SALE_ENDS_AT_OUT_OF_RANGE",
                "結束時間必須介於現在與活動原本的結束時間之間");
        }
        sale.endEarly(endsAt);
        return sale;
    }
}
