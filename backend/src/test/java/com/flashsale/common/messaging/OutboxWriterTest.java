package com.flashsale.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWriterTest {

    @Test
    void storesTheCurrentTraceContextWithTheOutboxEvent() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("abc123");
        when(context.spanId()).thenReturn("def456");
        when(context.sampled()).thenReturn(true);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        OutboxWriter writer = new OutboxWriter(repository, new ObjectMapper(), tracer);

        writer.write("Order", "1", EventTypes.CREATE_ORDER_REQUESTED, new Payload("hello"));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(event ->
            event.getTraceContext().equals(new StoredTraceContext(1, "abc123", "def456", true))));
    }

    @Test
    void leavesTraceContextNullWhenThereIsNoCurrentSpan() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);
        OutboxWriter writer = new OutboxWriter(repository, new ObjectMapper(), tracer);

        writer.write("Order", "1", EventTypes.CREATE_ORDER_REQUESTED, new Payload("hello"));

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(event -> event.getTraceContext() == null));
    }

    record Payload(String value) {}
}
