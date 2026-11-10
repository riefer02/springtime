package com.springtime.bigquery.retail.purchase.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality purchase order metrics. */
@Component
public class PurchaseOrderMetrics {

    private final MeterRegistry registry;

    public PurchaseOrderMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String event) {
        Counter.builder("retail.purchase.orders")
                .tag("event", event.toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
    }
}
