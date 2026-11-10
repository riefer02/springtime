package com.springtime.bigquery.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small bridge between the application and the OpenTelemetry/Micrometer tracing runtime.
 *
 * <p>Publishing uses {@link #currentAttributes()} to inject a W3C {@code traceparent} (and
 * {@code tracestate} when the propagator adds one). Consuming uses
 * {@link #startConsumerSpan(String, Map)} so the broker message becomes the remote parent of the
 * consumer span. The helper also keeps the MDC trace/span keys populated while that consumer span
 * is current, which is what the JSON encoder emits.</p>
 *
 * <p>When tracing is disabled the OpenTelemetry no-op implementation is used: the consumer span is
 * invalid, no HTTP request is made, and logs simply omit trace fields.</p>
 */
@Component
public class TraceContext {

    public static final String TRACE_ID_MDC_KEY = "traceId";
    public static final String SPAN_ID_MDC_KEY = "spanId";

    private static final String TRACER_NAME = "com.springtime.bigquery";

    private static final TextMapGetter<Map<String, String>> ATTRIBUTE_GETTER =
            new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    return carrier == null ? null : carrier.get(key);
                }
            };

    private final OpenTelemetry openTelemetry;

    @Autowired
    public TraceContext(ObjectProvider<OpenTelemetry> openTelemetry) {
        this(openTelemetry.getIfAvailable(OpenTelemetry::noop));
    }

    private TraceContext(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    /** Test and non-Spring use: propagate nothing and create no spans. */
    public static TraceContext noop() {
        return new TraceContext(OpenTelemetry.noop());
    }

    /** Test and explicit-configuration use with an already configured SDK. */
    public static TraceContext of(OpenTelemetry openTelemetry) {
        return new TraceContext(openTelemetry);
    }

    /** Attributes to attach to an outgoing broker message for the currently active span. */
    public Map<String, String> currentAttributes() {
        Map<String, String> carrier = new LinkedHashMap<>();
        propagator().inject(Context.current(), carrier, Map::put);
        return carrier.isEmpty() ? Map.of() : Map.copyOf(carrier);
    }

    /** Merge the active trace context into caller-supplied attributes, overwriting stale values. */
    public Map<String, String> withCurrentTrace(Map<String, String> attributes) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (attributes != null) {
            merged.putAll(attributes);
        }
        merged.putAll(currentAttributes());
        return merged.isEmpty() ? Map.of() : Map.copyOf(merged);
    }

    /**
     * Start a consumer span using the trace context carried by the broker message as its remote
     * parent. The returned scope restores the previous MDC values even when processing fails.
     */
    public SpanScope startConsumerSpan(String name, Map<String, String> carrier) {
        Map<String, String> attributes = carrier == null ? Map.of() : carrier;
        Context parent = propagator().extract(Context.current(), attributes, ATTRIBUTE_GETTER);
        Tracer tracer = openTelemetry.getTracer(TRACER_NAME);
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.CONSUMER)
                .setParent(parent)
                .startSpan();
        String traceId = span.getSpanContext().getTraceId();
        String spanId = span.getSpanContext().getSpanId();
        boolean valid = span.getSpanContext().isValid() && !isAllZero(traceId) && !isAllZero(spanId);

        // Capture the caller's MDC before making the span current: scope decorators (for example
        // Micrometer's Slf4J decorator installed by a Spring context) populate traceId/spanId when
        // the scope opens, so reading afterwards would capture our own ids and restore them on
        // close, leaking correlation fields into the next request or unit of work.
        String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        String previousSpanId = MDC.get(SPAN_ID_MDC_KEY);

        Scope scope = valid ? parent.with(span).makeCurrent() : Scope.noop();
        if (valid) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
            MDC.put(SPAN_ID_MDC_KEY, spanId);
        }
        return new SpanScope(span, scope, previousTraceId, previousSpanId);
    }

    private static boolean isAllZero(String value) {
        return value == null || value.isBlank() || value.chars().allMatch(character -> character == '0');
    }

    private TextMapPropagator propagator() {
        return openTelemetry.getPropagators().getTextMapPropagator();
    }

    /** Scope that ends the consumer span and restores MDC on close. */
    public static final class SpanScope implements AutoCloseable {

        private final Span span;
        private final Scope scope;
        private final String previousTraceId;
        private final String previousSpanId;
        private boolean closed;

        private SpanScope(Span span, Scope scope, String previousTraceId, String previousSpanId) {
            this.span = span;
            this.scope = scope;
            this.previousTraceId = previousTraceId;
            this.previousSpanId = previousSpanId;
        }

        public SpanScope setAttribute(String key, String value) {
            if (value != null) {
                span.setAttribute(key, value);
            }
            return this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            scope.close();
            restoreMdc(TRACE_ID_MDC_KEY, previousTraceId);
            restoreMdc(SPAN_ID_MDC_KEY, previousSpanId);
            span.end();
        }

        private static void restoreMdc(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}
