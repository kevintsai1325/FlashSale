package com.flashsale.common.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    @Test
    void publishesUnderTheStoredTraceParentAndEndsTheProducerSpan() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        Tracer tracer = mock(Tracer.class);
        TraceContext.Builder contextBuilder = mock(TraceContext.Builder.class);
        TraceContext parent = mock(TraceContext.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span span = mock(Span.class);
        when(tracer.traceContextBuilder()).thenReturn(contextBuilder);
        when(contextBuilder.traceId("trace-id")).thenReturn(contextBuilder);
        when(contextBuilder.spanId("span-id")).thenReturn(contextBuilder);
        when(contextBuilder.sampled(true)).thenReturn(contextBuilder);
        when(contextBuilder.build()).thenReturn(parent);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.setParent(parent)).thenReturn(spanBuilder);
        when(spanBuilder.name("outbox publish")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));
        OutboxEvent event = OutboxEvent.create("Order", "1", EventTypes.CREATE_ORDER_REQUESTED,
            "{}", new StoredTraceContext(1, "trace-id", "span-id", true), null);
        when(repository.findUnpublishedBatchForUpdate(anyInt())).thenReturn(List.of(event));
        OutboxPublisher publisher = new OutboxPublisher(
            repository, rabbitTemplate, mock(org.springframework.kafka.core.KafkaTemplate.class),
            mock(ApplicationContext.class), tracer);

        publisher.publishBatch();

        verify(spanBuilder).setParent(parent);
        verify(rabbitTemplate).send(any(String.class), any(String.class), any());
        verify(span).end();
        // 發出去之後才標記：兩者在同一個交易裡，所以「標記了但沒送出去」不該是一個可能的狀態。
        assertThat(event.getPublishedAt()).isNotNull();
    }
}
