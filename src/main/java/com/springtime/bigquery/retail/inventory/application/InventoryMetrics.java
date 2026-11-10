package com.springtime.bigquery.retail.inventory.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Inventory ledger metrics. Tags stay low-cardinality on purpose. */
@Component
public class InventoryMetrics {

    private static final String LAST_SUCCESS =
            "retail.inventory.projection.last.success.timestamp.seconds";

    private final MeterRegistry registry;
    private final AtomicLong lastProjectionSuccess = new AtomicLong();

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(LAST_SUCCESS, lastProjectionSuccess, AtomicLong::get)
                .description("Epoch seconds of the last successful on-hand projection refresh")
                .register(registry);
    }

    public void recordMovementsRead(int rows) {
        Counter.builder("retail.inventory.movements.read")
                .register(registry)
                .increment(rows);
    }

    public void recordReconciliation(String status, int driftPairs) {
        Counter.builder("retail.inventory.reconciliation")
                .tag("status", status.toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
        if (driftPairs > 0) {
            Counter.builder("retail.inventory.reconciliation.drift.pairs")
                    .register(registry)
                    .increment(driftPairs);
        }
    }

    public void recordProjection(String status, long affectedRows) {
        lastProjectionSuccess.set(Instant.now().getEpochSecond());
        Counter.builder("retail.inventory.projection")
                .tag("status", status.toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
        if (affectedRows > 0) {
            Counter.builder("retail.inventory.projection.rows")
                    .register(registry)
                    .increment(affectedRows);
        }
    }
}
