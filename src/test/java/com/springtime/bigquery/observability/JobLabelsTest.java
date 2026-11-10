package com.springtime.bigquery.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobLabelsTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void everySurfaceNormalisesTheRequestIdTheSameWay() {
        MDC.put("requestId", "AbC.1234-5678");

        String rest = JobLabels.of("rest-api").get("request_id");
        String ledger = JobLabels.of("inventory-ledger").get("request_id");

        assertThat(rest).isEqualTo("r-abc_1234-5678");
        assertThat(ledger).isEqualTo(rest);
    }

    @Test
    void normalisesSurfaceAndExtraLabels() {
        Map<String, String> labels = JobLabels.of("Inventory Ledger", Map.of("Batch Size", "100"));

        assertThat(labels)
                .containsEntry("app", "springtime")
                .containsEntry("surface", "inventory_ledger")
                .containsEntry("batch_size", "100");
    }

    @Test
    void capsLongValuesAtThePlatformLimit() {
        MDC.put("requestId", "x".repeat(200));

        assertThat(JobLabels.of("rest-api").get("request_id")).hasSize(63);
    }
}
