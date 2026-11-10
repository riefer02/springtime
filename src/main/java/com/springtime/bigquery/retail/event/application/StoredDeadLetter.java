package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;

import java.time.Instant;

/** A dead letter plus its local replay state. */
public record StoredDeadLetter(
        DeadLetterEnvelope envelope,
        Instant replayedAt,
        String replayStatus
) {
    public static final String PENDING = "PENDING";
    public static final String REPLAYED = "REPLAYED";

    public static StoredDeadLetter pending(DeadLetterEnvelope envelope) {
        return new StoredDeadLetter(envelope, null, PENDING);
    }

    public StoredDeadLetter replayedAt(Instant instant) {
        return new StoredDeadLetter(envelope, instant, REPLAYED);
    }
}
