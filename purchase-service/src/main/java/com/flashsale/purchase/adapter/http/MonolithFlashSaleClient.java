package com.flashsale.purchase.adapter.http;

import com.flashsale.purchase.application.FlashSaleClient;
import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.exception.NotFoundException;
import com.flashsale.purchase.exception.ServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 整個步驟 1 唯一新增的同步跨服務依賴，而且它在搶購的熱路徑上。
 *
 * 這個類別只負責「打過去、把失敗翻譯成有意義的例外」。快取與失敗時的降級在
 * {@link CachingFlashSaleClient} —— 拆成兩個類別是為了讓那段有狀態的邏輯能在沒有 HTTP 的
 * 情況下被測試，不是為了分層而分層。
 *
 * 逾時預算：連線 500 ms、讀取 1000 ms。搶購是使用者按著等回應的動作，這個呼叫的預算必須
 * 明顯小於使用者的耐心；而且它被包在資料庫交易裡（見 CreatePurchaseRequestService），
 * 所以它同時也是那個交易長度的上限。
 *
 * 「查不到」與「查不動」必須是兩種不同的例外：前者是權威的答案（活動不存在），
 * 後者只代表我們現在問不到。把它們塌縮成同一種，就會讓降級邏輯把「活動已刪除」
 * 當成「下游掛了」而繼續用快取放行。
 */
@Component
public class MonolithFlashSaleClient implements FlashSaleClient {

    private final RestClient restClient;

    public MonolithFlashSaleClient(RestClient.Builder restClientBuilder,
                                    @Value("${app.flash-sale.base-url}") String baseUrl,
                                    @Value("${app.flash-sale.connect-timeout-ms}") long connectTimeoutMs,
                                    @Value("${app.flash-sale.read-timeout-ms}") long readTimeoutMs) {
        // 用自動設定的 builder 而不是自己 new：追蹤的 header 傳播是掛在 builder 上的，
        // 自己 new 一個會讓 trace 在跨服務的那一跳斷掉。
        this.restClient = restClientBuilder
            .baseUrl(baseUrl)
            .requestFactory(ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs))))
            .build();
    }

    @Override
    public FlashSaleSnapshot fetch(Long flashSaleId) {
        try {
            InternalFlashSaleResponse response = restClient.get()
                .uri("/internal/flash-sales/{id}", flashSaleId)
                .retrieve()
                .body(InternalFlashSaleResponse.class);
            if (response == null) {
                throw new ServiceUnavailableException("FLASH_SALE_LOOKUP_UNAVAILABLE",
                    "目前無法取得搶購活動 " + flashSaleId + " 的資料，請稍後再試");
            }
            return new FlashSaleSnapshot(response.id(), response.productId(), response.productName(), response.salePrice(),
                response.startsAt(), response.endsAt(), response.purchaseLimitPerUser());
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new NotFoundException("FLASH_SALE_NOT_FOUND", "搶購活動 " + flashSaleId + " 不存在");
        } catch (NotFoundException | ServiceUnavailableException rethrow) {
            throw rethrow;
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("FLASH_SALE_LOOKUP_UNAVAILABLE",
                "目前無法取得搶購活動 " + flashSaleId + " 的資料，請稍後再試");
        }
    }

    @Override
    public int availableQuantity(Long flashSaleId) {
        try {
            AvailableQuantityResponse response = restClient.get()
                .uri("/internal/flash-sales/{id}/available-quantity", flashSaleId)
                .retrieve()
                .body(AvailableQuantityResponse.class);
            if (response == null) {
                throw new ServiceUnavailableException("INVENTORY_LOOKUP_UNAVAILABLE",
                    "目前無法取得活動 " + flashSaleId + " 的庫存資料，請稍後再試");
            }
            return response.availableQuantity();
        } catch (HttpClientErrorException.NotFound notFound) {
            throw new NotFoundException("INVENTORY_NOT_FOUND", "搶購活動 " + flashSaleId + " 的庫存資料不存在");
        } catch (NotFoundException | ServiceUnavailableException rethrow) {
            throw rethrow;
        } catch (RuntimeException exception) {
            throw new ServiceUnavailableException("INVENTORY_LOOKUP_UNAVAILABLE",
                "目前無法取得活動 " + flashSaleId + " 的庫存資料，請稍後再試");
        }
    }
}
