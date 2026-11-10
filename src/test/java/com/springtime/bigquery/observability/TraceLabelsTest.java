package com.springtime.bigquery.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLabelsTest {

    @AfterEach
    void tearDown() {
        MDC.remove(TraceContext.TRACE_ID_MDC_KEY);
        MDC.remove(TraceContext.SPAN_ID_MDC_KEY);
    }

    @Test
    void addsLowercaseBigQuerySafeTraceAndSpanLabels() {
        MDC.put(TraceContext.TRACE_ID_MDC_KEY, "0AF7651916CD43DD8448EB211C80319C");
        MDC.put(TraceContext.SPAN_ID_MDC_KEY, "B7AD6B7169203331");
        Map<String, String> labels = new HashMap<>();

        TraceLabels.addTraceLabels(labels);

        assertThat(labels)
                .containsEntry(TraceLabels.TRACE_ID_LABEL, "0af7651916cd43dd8448eb211c80319c")
                .containsEntry(TraceLabels.SPAN_ID_LABEL, "b7ad6b7169203331");
    }

    @Test
    void omitsLabelsWhenNoTraceIsActive() {
        Map<String, String> labels = new HashMap<>();

        TraceLabels.addTraceLabels(labels);

        assertThat(labels).isEmpty();
    }
}
