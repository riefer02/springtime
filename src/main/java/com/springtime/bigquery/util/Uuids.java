package com.springtime.bigquery.util;

import java.util.UUID;

/** Small UUID validation helper for path variables and idempotency boundaries. */
public final class Uuids {

    private Uuids() {
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
