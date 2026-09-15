package com.flashsale.flashsale.application;

import com.flashsale.catalog.application.ProductRepository;
import com.flashsale.catalog.domain.Product;
import com.flashsale.common.client.OrderServiceClient;
import com.flashsale.common.exception.NotFoundException;
import com.flashsale.flashsale.application.dto.FlashSaleDetail;
import com.flashsale.flashsale.application.dto.FlashSaleSummary;
import com.flashsale.flashsale.domain.FlashSale;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 店面的活動列表與詳情。
 *
 * P5 之後庫存數量來自 order-service —— 庫存跟著「建單時鎖住它」的那個交易走了。
 * 列表用**批次**查詢而不是每筆一次：一次跨服務呼叫換成 N 次，是拆分後最容易寫出來的
 * 效能退化，而它在單機開發時完全看不出來。
 *
 * 拿不到庫存時顯示 0 而不是讓整頁失敗：店面的庫存數字本來就是近似值（真正的預扣在 Redis），
 * 而「商品列表打不開」是比「數量顯示不準」嚴重得多的故障。
 */
@Service
public class FlashSaleQueryService {

    private final FlashSaleRepository flashSaleRepository;
    private final ProductRepository productRepository;
    private final OrderServiceClient orderServiceClient;

    public FlashSaleQueryService(FlashSaleRepository flashSaleRepository, ProductRepository productRepository,
                                  OrderServiceClient orderServiceClient) {
        this.flashSaleRepository = flashSaleRepository;
        this.productRepository = productRepository;
        this.orderServiceClient = orderServiceClient;
    }

    public List<FlashSaleSummary> listAll() {
        List<FlashSale> sales = flashSaleRepository.findAll();
        Map<Long, OrderServiceClient.InventoryView> inventories =
            orderServiceClient.inventories(sales.stream().map(FlashSale::getId).toList());

        return sales.stream()
            .map(sale -> {
                Product product = productFor(sale);
                OrderServiceClient.InventoryView inventory = inventories.get(sale.getId());
                return new FlashSaleSummary(sale.getId(), sale.getProductId(), product.getName(), sale.getSalePrice(),
                    sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(),
                    inventory == null ? 0 : inventory.totalQuantity(),
                    sale.effectiveStatus(Instant.now()).name());
            })
            .toList();
    }

    public FlashSaleDetail getDetail(Long id) {
        FlashSale sale = flashSaleRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + id + " 不存在"));
        Product product = productFor(sale);
        OrderServiceClient.InventoryView inventory = orderServiceClient.inventories(List.of(id)).get(id);
        int totalQuantity = inventory != null ? inventory.totalQuantity() : 0;
        int availableQuantity = inventory != null ? inventory.availableQuantity() : 0;
        return new FlashSaleDetail(sale.getId(), product.getName(), product.getDescription(), sale.getSalePrice(),
            sale.getStartsAt(), sale.getEndsAt(), sale.getPurchaseLimitPerUser(),
            totalQuantity, availableQuantity, sale.effectiveStatus(Instant.now()).name());
    }

    private Product productFor(FlashSale sale) {
        return productRepository.findById(sale.getProductId())
            .orElseThrow(() -> new NotFoundException("PRODUCT_NOT_FOUND", "商品 " + sale.getProductId() + " 不存在"));
    }
}
