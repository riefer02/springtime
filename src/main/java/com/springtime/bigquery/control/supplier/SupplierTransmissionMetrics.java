package com.springtime.bigquery.control.supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality supplier outbox metrics. */
@Component
public class SupplierTransmissionMetrics {

    private final MeterRegistry registry;

    public SupplierTransmissionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String event) {
        Counter.builder("retail.supplier.transmissions")
                .tag("event", event.toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
    }
}
