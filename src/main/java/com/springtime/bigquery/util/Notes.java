package com.springtime.bigquery.util;

/** Bounded free-text normalization for operator notes. */
public final class Notes {

    /** Shared note ceiling for request DTOs and services; keep the annotation and truncation equal. */
    public static final int MAX_NOTE = 500;

    private Notes() {
    }

    public static String safeNote(String note, int maxLength) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
