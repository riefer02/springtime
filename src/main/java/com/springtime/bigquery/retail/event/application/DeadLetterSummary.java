package com.springtime.bigquery.retail.event.application;

import java.time.Instant;

/**
 * One dead-letter row without the stored envelope. List responses use this so a page never
 * downloads payload bodies; replay loads the envelope through {@link DeadLetterStore#find}.
 */
public record DeadLetterSummary(
        String eventId,
        String eventType,
        String schemaVersion,
        Instant failedAt,
        int attempts,
        String reason,
        String errorType,
        String originalTopic,
        Instant replayedAt,
        String replayStatus
) {
}
