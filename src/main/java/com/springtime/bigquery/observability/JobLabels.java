package com.springtime.bigquery.observability;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One builder for BigQuery job labels: app, surface, request id, and trace ids. BigQuery accepts
 * lowercase letters, digits, underscores, and dashes, up to 63 characters per value.
 */
public final class JobLabels {

    public static final String APP = "springtime";
    private static final int MAX_VALUE_LENGTH = 63;
    private static final String REQUEST_ID_MDC_KEY = "requestId";

    private JobLabels() {
    }

    public static Map<String, String> of(String surface) {
        return of(surface, Map.of());
    }

    /** Labels for one job, with extra adapter-specific values (for example {@code batch_size}). */
    public static Map<String, String> of(String surface, Map<String, String> extra) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app", APP);
        labels.put("surface", normalise(surface));
        String requestId = MDC.get(REQUEST_ID_MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            labels.put("request_id", normalise("r-" + requestId));
        }
        TraceLabels.addTraceLabels(labels);
        extra.forEach((key, value) -> {
            if (value != null) {
                labels.put(normaliseKey(key), normalise(value));
            }
        });
        return labels;
    }

    static String normalise(String value) {
        String cleaned = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_");
        return cleaned.substring(0, Math.min(MAX_VALUE_LENGTH, cleaned.length()));
    }

    private static String normaliseKey(String key) {
        String cleaned = key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_");
        return cleaned.substring(0, Math.min(MAX_VALUE_LENGTH, cleaned.length()));
    }
}
