package com.springtime.bigquery.util;

/** Keeps untrusted identifiers from forging or bloating log lines. */
public final class SafeLog {

    private static final int MAX_LENGTH = 64;

    private SafeLog() {
    }

    public static String id(String value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.replaceAll("[\\r\\n\\t]", "_");
        return cleaned.length() <= MAX_LENGTH ? cleaned : cleaned.substring(0, MAX_LENGTH);
    }
}
