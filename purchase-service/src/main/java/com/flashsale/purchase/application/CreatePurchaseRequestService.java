package com.flashsale.purchase.application;

import com.flashsale.purchase.application.dto.FlashSaleSnapshot;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.exception.ConflictException;
import com.flashsale.purchase.messaging.CreateOrderRequestedEvent;
import com.flashsale.purchase.messaging.EventTypes;
import com.flashsale.purchase.messaging.OutboxWriter;
import com.flashsale.purchase.messaging.PurchaseRequestCreatedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 與拆分前的流程逐步相同，唯一的差別是活動資料從跨行程呼叫取得（FlashSaleClient），
 * 而不是同行程的 repository。
 *
 * 注意 @Transactional 的範圍：活動查詢（可能是一次 HTTP 呼叫）與 Redis 預扣都在交易裡，
 * 這是拆分前就有的形狀，拆分沒有讓它變好也沒有變壞——但它現在的意義不一樣了：
 * 一次慢的跨服務呼叫會把資料庫交易一起拖長。逾時預算（1 秒）因此也是交易長度的上限，
 * 這是 MonolithFlashSaleClient 那個 1000 ms 真正的理由。
 */
@Service
public class CreatePurchaseRequestService {

    private final FlashSaleClient flashSaleClient;
    private final InventoryStockGateway inventoryStockGateway;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final OutboxWriter outboxWriter;

    public CreatePurchaseRequestService(FlashSaleClient flashSaleClient, InventoryStockGateway inventoryStockGateway,
                                         PurchaseRequestRepository purchaseRequestRepository, OutboxWriter outboxWriter) {
        this.flashSaleClient = flashSaleClient;
        this.inventoryStockGateway = inventoryStockGateway;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public PurchaseRequest createPurchaseRequest(Long userId, Long flashSaleId, String idempotencyKey, int quantity) {
        var existing = purchaseRequestRepository
            .findByUserIdAndFlashSaleIdAndIdempotencyKey(userId, flashSaleId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        FlashSaleSnapshot flashSale = flashSaleClient.fetch(flashSaleId);

        if (!flashSale.isPurchasableAt(Instant.now())) {
            throw new ConflictException("FLASH_SALE_NOT_ACTIVE", "搶購活動目前未開放搶購");
        }

        if (quantity > flashSale.purchaseLimitPerUser()) {
            throw new ConflictException("PURCHASE_QUANTITY_EXCEEDS_LIMIT",
                "數量 " + quantity + " 超過此活動每人限購 " + flashSale.purchaseLimitPerUser() + " 件的上限");
        }

        if (purchaseRequestRepository.existsSucceededForUserAndFlashSale(userId, flashSaleId)) {
            return publishCreated(purchaseRequestRepository.save(PurchaseRequest.reject(userId, flashSaleId, idempotencyKey)));
        }

        StockReservationResult reservation = inventoryStockGateway.reserve(flashSaleId, quantity);
        if (reservation == StockReservationResult.INSUFFICIENT_STOCK) {
            return publishCreated(purchaseRequestRepository.save(PurchaseRequest.soldOut(userId, flashSaleId, idempotencyKey)));
        }

        PurchaseRequest request = publishCreated(
            purchaseRequestRepository.save(PurchaseRequest.pending(userId, flashSaleId, idempotencyKey)));

        outboxWriter.write("PurchaseRequest", request.getRequestId().toString(), EventTypes.CREATE_ORDER_REQUESTED,
            new CreateOrderRequestedEvent(request.getRequestId(), userId, flashSaleId, flashSale.productId(),
                flashSale.productName(), quantity, flashSale.salePrice()));

        return request;
    }

    /**
     * 每一次搶購嘗試都發一個領域事件，包含被限購擋下與售罄的。下游要算的是
     * 「多少人來搶、多少人買到」，被擋下的那些正是分母 —— 只發成功的事件，轉換率永遠是 100%。
     *
     * 與上面那個 CreateOrderRequested 的差別：那是送給 backend 的命令（RabbitMQ），
     * 這是說給所有人聽的事實（Kafka），而且只有 PENDING 那條路徑會有前者。
     */
    private PurchaseRequest publishCreated(PurchaseRequest request) {
        outboxWriter.write("PurchaseRequest", request.getRequestId().toString(), EventTypes.PURCHASE_REQUEST_CREATED,
            new PurchaseRequestCreatedEvent(request.getRequestId(), request.getUserId(), request.getFlashSaleId(),
                request.getStatus().name(), request.getCreatedAt()),
            String.valueOf(request.getFlashSaleId()));
        return request;
    }
}
