package com.flashsale.flashsale.application;

import com.flashsale.flashsale.domain.FlashSale;
import java.util.List;
import java.util.Optional;

public interface FlashSaleRepository {
    List<FlashSale> findAll();
    Optional<FlashSale> findById(Long id);
    FlashSale save(FlashSale flashSale);

    /**
     * 強制把待處理的寫入送到資料庫。建立活動時需要它：要拿到資料庫產生的 id 才能
     * 去 order-service 宣告庫存，而那一步必須發生在本地交易提交之前。
     */
    void flush();
}
