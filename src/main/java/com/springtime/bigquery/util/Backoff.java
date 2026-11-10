package com.springtime.bigquery.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Shared bounded exponential backoff with proportional jitter. */
public final class Backoff {

    private Backoff() {
    }

    public static long delayMillis(int attempt, Duration initial, double multiplier,
                                   Duration maxDelay, double jitter) {
        double base = initial.toMillis() * Math.pow(multiplier, Math.max(0, attempt - 1));
        double capped = Math.min(base, maxDelay.toMillis());
        double jitterFactor = 1.0;
        if (jitter > 0) {
            jitterFactor += ThreadLocalRandom.current().nextDouble(-jitter, jitter);
        }
        long value = (long) (capped * jitterFactor);
        return Math.max(0, Math.min(value, maxDelay.toMillis()));
    }
}
