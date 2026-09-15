package com.flashsale.purchase.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.purchase.application.PurchaseRequestRepository;
import com.flashsale.purchase.domain.PurchaseRequest;
import com.flashsale.purchase.domain.PurchaseRequestStatus;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 終態的唯一寫入者，冪等靠的是狀態本身。重複投遞是 RabbitMQ 的常態，不是異常，
 * 所以「第二次不會再寫一次」必須有測試守著。
 */
class PurchaseResolvedConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxWriter outboxWriter = org.mockito.Mockito.mock(OutboxWriter.class);

    private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PurchaseRequest pendingRequest() {
        PurchaseRequest request = PurchaseRequest.pending(1L, 10L, "idem");
        ReflectionTestUtils.setField(request, "id", 77L);
        ReflectionTestUtils.setField(request, "requestId", REQUEST_ID);
        return request;
    }

    private static PurchaseRequestRepository repositoryFor(PurchaseRequest request, AtomicInteger saves) {
        return new PurchaseRequestRepository() {
            @Override public PurchaseRequest save(PurchaseRequest r) { saves.incrementAndGet(); return r; }
            @Override public Optional<PurchaseRequest> findByRequestId(UUID requestId) { return Optional.of(request); }
            @Override public Optional<PurchaseRequest> findByUserIdAndFlashSaleIdAndIdempotencyKey(Long u, Long f, String k) { return Optional.empty(); }
            @Override public boolean existsSucceededForUserAndFlashSale(Long u, Long f) { return false; }
        };
    }

    private Message messageFor(PurchaseResolvedEvent event) throws Exception {
        return new Message(objectMapper.writeValueAsBytes(event), new MessageProperties());
    }

    @Test
    void marksSucceededWithTheOrderId() throws Exception {
        PurchaseRequest request = pendingRequest();
        AtomicInteger saves = new AtomicInteger();
        PurchaseResolvedConsumer consumer = new PurchaseResolvedConsumer(repositoryFor(request, saves), outboxWriter, objectMapper);

        consumer.handle(messageFor(new PurchaseResolvedEvent(REQUEST_ID, "SUCCEEDED", 5001L)));

        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.SUCCEEDED);
        assertThat(request.getOrderId()).isEqualTo(5001L);
        assertThat(saves.get()).isEqualTo(1);
    }

    @Test
    void marksFailedWhenCompensationReportsFailure() throws Exception {
        PurchaseRequest request = pendingRequest();
        AtomicInteger saves = new AtomicInteger();
        PurchaseResolvedConsumer consumer = new PurchaseResolvedConsumer(repositoryFor(request, saves), outboxWriter, objectMapper);

        consumer.handle(messageFor(new PurchaseResolvedEvent(REQUEST_ID, "FAILED", null)));

        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.FAILED);
        assertThat(saves.get()).isEqualTo(1);
    }

    @Test
    void ignoresARedeliveryOnceTheRequestIsAlreadyTerminal() throws Exception {
        PurchaseRequest request = pendingRequest();
        AtomicInteger saves = new AtomicInteger();
        PurchaseResolvedConsumer consumer = new PurchaseResolvedConsumer(repositoryFor(request, saves), outboxWriter, objectMapper);

        consumer.handle(messageFor(new PurchaseResolvedEvent(REQUEST_ID, "SUCCEEDED", 5001L)));
        consumer.handle(messageFor(new PurchaseResolvedEvent(REQUEST_ID, "SUCCEEDED", 5001L)));
        // 補償與成功互相追上時也一樣：先到的那個定案，後到的不得覆寫。
        consumer.handle(messageFor(new PurchaseResolvedEvent(REQUEST_ID, "FAILED", null)));

        assertThat(request.getStatus()).isEqualTo(PurchaseRequestStatus.SUCCEEDED);
        assertThat(request.getOrderId()).isEqualTo(5001L);
        assertThat(saves.get()).isEqualTo(1);
    }
}
