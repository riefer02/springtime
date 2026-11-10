package com.springtime.bigquery.util;

import java.util.List;
import java.util.Map;

/** Approximate retained/encoded size of a JSON-shaped value, used for budgets and queue bounds. */
public final class ByteSizes {

    private static final long NULL = 4;
    private static final long SCALAR = 16;
    private static final long CONTAINER = 16;

    private ByteSizes() {
    }

    public static long estimate(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof String text) {
            return SCALAR + text.length();
        }
        if (value instanceof Map<?, ?> map) {
            long total = CONTAINER;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += estimate(entry.getKey()) + estimate(entry.getValue());
            }
            return total;
        }
        if (value instanceof List<?> list) {
            long total = CONTAINER;
            for (Object element : list) {
                total += estimate(element);
            }
            return total;
        }
        return SCALAR;
    }
}
