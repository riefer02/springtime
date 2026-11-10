package com.springtime.bigquery.retail.event.consumer;

import com.springtime.bigquery.messaging.InMemoryMessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.observability.TracingTestSupport;
import com.springtime.bigquery.retail.event.application.RetailEventProcessor;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LocalRetailEventRelayTest {

    private final InMemoryMessagePublisher publisher = mock(InMemoryMessagePublisher.class);
    private final RetailEventProcessor processor = mock(RetailEventProcessor.class);

    @Test
    void globalConsumerDisabledStopsTheLocalRelay() {
        LocalRetailEventRelay relay = new LocalRetailEventRelay(
                publisher, processor, properties(false, true));

        relay.drain();

        verify(publisher, never()).poll();
        verify(processor, never()).process(any());
    }

    @Test
    void localConsumerDisabledStopsTheRelay() {
        LocalRetailEventRelay relay = new LocalRetailEventRelay(
                publisher, processor, properties(true, false));

        relay.drain();

        verify(publisher, never()).poll();
    }

    @Test
    void usesBrokerTraceParentAsTheConsumerSpanParent() {
        TracingTestSupport.CapturingSpanExporter exporter =
                new TracingTestSupport.CapturingSpanExporter();
        OpenTelemetrySdk sdk = TracingTestSupport.sdk(exporter);
        try {
            MessagingProperties properties = properties(true, true);
            InMemoryMessagePublisher tracedPublisher = new InMemoryMessagePublisher(
                    properties, TraceContext.of(sdk));
            LocalRetailEventRelay relay = new LocalRetailEventRelay(
                    tracedPublisher, processor, properties, TraceContext.of(sdk));
            EventEnvelope event = new EventEnvelope(
                    "evt-1", "SALE", Instant.parse("2026-11-09T11:00:00Z"),
                    "1", "ST-001", "SKU-0001", 2L, Map.of());
            tracedPublisher.publish("retail-events", event,
                    Map.of("traceparent", TracingTestSupport.TRACEPARENT));

            relay.drain();

            verify(processor).process(event);
            SpanData consumer = exporter.spans().stream()
                    .filter(span -> "retail-event.consume".equals(span.getName()))
                    .findFirst()
                    .orElseThrow();
            assertThat(consumer.getParentSpanId()).isEqualTo(TracingTestSupport.PARENT_SPAN_ID);
            assertThat(consumer.getTraceId()).isEqualTo(TracingTestSupport.TRACE_ID);
        } finally {
            sdk.close();
        }
    }

    private static MessagingProperties properties(boolean consumerEnabled,
                                                   boolean localConsumerEnabled) {
        return new MessagingProperties(
                false, "retail-events", "retail-events-dlq", "retail-events-worker",
                consumerEnabled, localConsumerEnabled, 500, 1_000);
    }
}
