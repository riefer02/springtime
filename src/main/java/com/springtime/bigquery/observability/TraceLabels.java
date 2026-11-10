package com.springtime.bigquery.observability;

import org.slf4j.MDC;

import java.util.Locale;
import java.util.Map;

/**
 * Adds the current trace/span ids to BigQuery job labels. BigQuery requires lowercase label keys
 * and values made of letters, digits, underscores, and dashes, so values are normalised and capped
 * at the platform limit.
 */
public final class TraceLabels {

    public static final String TRACE_ID_LABEL = "trace_id";
    public static final String SPAN_ID_LABEL = "span_id";
    private static final int MAX_LABEL_LENGTH = 63;

    private TraceLabels() {
    }

    public static void addTraceLabels(Map<String, String> labels) {
        add(labels, TRACE_ID_LABEL, MDC.get(TraceContext.TRACE_ID_MDC_KEY));
        add(labels, SPAN_ID_LABEL, MDC.get(TraceContext.SPAN_ID_MDC_KEY));
    }

    private static void add(Map<String, String> labels, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (cleaned.isBlank()) {
            return;
        }
        labels.put(key, cleaned.substring(0, Math.min(MAX_LABEL_LENGTH, cleaned.length())));
    }
}
