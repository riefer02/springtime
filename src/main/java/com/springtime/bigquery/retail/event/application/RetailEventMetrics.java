package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Low-cardinality event-pipeline metrics. Never tag by event id or store id. */
@Component
public class RetailEventMetrics {

    private final MeterRegistry registry;

    public RetailEventMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordProcessed(String eventType, MovementWriteResult result) {
        counter("retail.events.processed",
                "event_type", tag(eventType),
                "result", tag(result.name()))
                .increment();
    }

    public void recordSinkFailure(String eventType, boolean retryable) {
        counter("retail.events.sink.failures",
                "event_type", tag(eventType),
                "retryable", Boolean.toString(retryable))
                .increment();
    }

    public void recordRetryExhausted(String eventType) {
        counter("retail.events.retry.exhausted", "event_type", tag(eventType)).increment();
    }

    public void recordDeadLettered(String eventType, String reason) {
        counter("retail.events.dead.lettered",
                "event_type", tag(eventType),
                "reason", tag(reason))
                .increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }

    private static String tag(String value) {
        if (value == null) {
            return "unknown";
        }
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }
}
