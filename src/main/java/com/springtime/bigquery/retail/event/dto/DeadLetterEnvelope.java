package com.springtime.bigquery.retail.event.dto;

import com.springtime.bigquery.messaging.MessagePayload;

import java.time.Instant;
import java.util.Objects;

/**
 * A durable record of an event that could not be processed. It keeps the original envelope so a
 * replay tool can submit it again without reconstructing the payload, and adds the failure context
 * operators need to triage it.
 *
 * <p>The original event id is both the dead-letter identity and the payload id; there is only one
 * id so a stored row can never disagree with itself.</p>
 */
public record DeadLetterEnvelope(
        String originalEventId,
        String eventType,
        String schemaVersion,
        Instant failedAt,
        int attempts,
        String reason,
        String errorType,
        String originalTopic,
        EventEnvelope event
) implements MessagePayload {

    public DeadLetterEnvelope {
        Objects.requireNonNull(originalEventId, "originalEventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(failedAt, "failedAt");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(originalTopic, "originalTopic");
        Objects.requireNonNull(event, "event");
    }

    @Override
    public String eventId() {
        return originalEventId;
    }
}
