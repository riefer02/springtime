package com.springtime.bigquery.retail.event.dto;

import java.time.Instant;

/** API view of a dead letter. The envelope is intentionally not exposed here. */
public record DeadLetterView(
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
