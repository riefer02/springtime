package com.springtime.bigquery.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Timestamp formatting shared by BigQuery bindings. */
public final class Timestamps {

    private Timestamps() {
    }

    /**
     * BigQuery accepts at most microsecond precision; Java's {@link Instant#toString()} can emit
     * nanoseconds, which BigQuery rejects as an invalid timestamp.
     */
    public static String bigQuery(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
