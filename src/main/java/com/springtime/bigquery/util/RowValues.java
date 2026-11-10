package com.springtime.bigquery.util;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Null-safe accessors for BigQuery rows returned as maps. */
public final class RowValues {

    private RowValues() {
    }

    public static String string(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    public static Double doubleValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    public static double doubleValueOrZero(Map<String, Object> row, String key) {
        Double value = doubleValue(row, key);
        return value == null ? 0.0 : value;
    }

    public static LocalDate date(Map<String, Object> row, String key) {
        String value = string(row, key);
        return value == null ? null : LocalDate.parse(value);
    }

    public static Instant instant(Map<String, Object> row, String key) {
        String value = string(row, key);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            return java.time.OffsetDateTime.parse(value.replace(' ', 'T')).toInstant();
        }
    }

    public static BigDecimal bigDecimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
