package com.springtime.bigquery.retail.inventory.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMetricsTest {

    @Test
    void aSuccessfulRefreshAdvancesTheFreshnessGauge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InventoryMetrics metrics = new InventoryMetrics(registry);

        metrics.recordProjection("INCREMENTAL", 10);

        assertThat(registry.get("retail.inventory.projection.last.success.timestamp.seconds")
                .gauge().value()).isGreaterThan(0);
    }
}
