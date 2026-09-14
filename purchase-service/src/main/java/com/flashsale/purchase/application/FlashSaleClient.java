package com.flashsale.purchase.application;

import com.flashsale.purchase.application.dto.FlashSaleSnapshot;

public interface FlashSaleClient {

    /**
     * 取得活動資料。查不到時丟 NotFoundException，查不動時丟 ServiceUnavailableException ——
     * 兩者對呼叫端是不同的意思，不可以塌縮成同一種。
     */
    FlashSaleSnapshot fetch(Long flashSaleId);

    /**
     * 目前的可售數量，用來在 Redis 的庫存鍵不存在時種入初始值。
     * 這個值刻意不快取：種入一個過期的數量會直接造成超賣或漏賣。
     */
    int availableQuantity(Long flashSaleId);
}
