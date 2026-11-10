package com.springtime.bigquery.retail.replenishment.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality replenishment run metrics. */
@Component
public class ReplenishmentMetrics {

    private final MeterRegistry registry;

    public ReplenishmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRun(String status, int proposalCount) {
        Counter.builder("retail.replenishment.runs")
                .tag("status", status.toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
        if (proposalCount > 0) {
            Counter.builder("retail.replenishment.proposals")
                    .register(registry)
                    .increment(proposalCount);
        }
    }
}
