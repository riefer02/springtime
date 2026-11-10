package com.springtime.bigquery.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextTest {

    private final TracingTestSupport.CapturingSpanExporter exporter =
            new TracingTestSupport.CapturingSpanExporter();
    private final OpenTelemetrySdk sdk = TracingTestSupport.sdk(exporter);
    private final TraceContext traceContext = TraceContext.of(sdk);

    @BeforeEach
    void clearMdc() {
        // Other suites may leave correlation baggage on the shared surefire thread; this test
        // asserts restoration, so it must start from a known-empty MDC.
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.remove(TraceContext.TRACE_ID_MDC_KEY);
        MDC.remove(TraceContext.SPAN_ID_MDC_KEY);
        sdk.close();
    }

    @Test
    void injectsTraceParentForTheCurrentSpan() {
        Span span = sdk.getTracer("test").spanBuilder("publish").startSpan();
        Map<String, String> attributes;
        try (Scope ignored = span.makeCurrent()) {
            attributes = traceContext.currentAttributes();

            assertThat(traceContext.withCurrentTrace(Map.of("custom", "value")))
                    .containsEntry("custom", "value")
                    .containsEntry("traceparent", attributes.get("traceparent"));
        } finally {
            span.end();
        }

        assertThat(attributes.get("traceparent"))
                .startsWith("00-" + span.getSpanContext().getTraceId() + "-");
    }

    @Test
    void extractsTraceParentAsTheConsumerSpanParentAndRestoresMdc() {
        try (TraceContext.SpanScope ignored = traceContext.startConsumerSpan(
                "retail-event.consume", Map.of("traceparent", TracingTestSupport.TRACEPARENT))) {
            assertThat(MDC.get(TraceContext.TRACE_ID_MDC_KEY)).isEqualTo(TracingTestSupport.TRACE_ID);
            assertThat(MDC.get(TraceContext.SPAN_ID_MDC_KEY))
                    .isNotBlank()
                    .isNotEqualTo(TracingTestSupport.PARENT_SPAN_ID);
            assertThat(Span.current().getSpanContext().getTraceId())
                    .isEqualTo(TracingTestSupport.TRACE_ID);
        }

        assertThat(MDC.get(TraceContext.TRACE_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(TraceContext.SPAN_ID_MDC_KEY)).isNull();

        SpanData consumer = exporter.spans().stream()
                .filter(span -> "retail-event.consume".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(consumer.getParentSpanId()).isEqualTo(TracingTestSupport.PARENT_SPAN_ID);
        assertThat(consumer.getTraceId()).isEqualTo(TracingTestSupport.TRACE_ID);
    }

    @Test
    void noopContextNeverThrowsOrPutsTraceIdsInMdc() {
        TraceContext noop = TraceContext.noop();

        assertThat(noop.currentAttributes()).isEmpty();
        try (TraceContext.SpanScope ignored = noop.startConsumerSpan(
                "retail-event.consume", Map.of("traceparent", TracingTestSupport.TRACEPARENT))) {
            assertThat(MDC.get(TraceContext.TRACE_ID_MDC_KEY)).isNull();
            assertThat(MDC.get(TraceContext.SPAN_ID_MDC_KEY)).isNull();
        }
    }
}
