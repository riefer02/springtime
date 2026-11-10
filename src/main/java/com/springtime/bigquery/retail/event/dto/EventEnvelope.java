package com.springtime.bigquery.retail.event.dto;

import com.springtime.bigquery.messaging.MessagePayload;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable envelope published to the topic. Event ids are the idempotency key for consumers. */
public record EventEnvelope(
        String eventId,
        String eventType,
        Instant occurredAt,
        String schemaVersion,
        String storeId,
        String productId,
        Long quantity,
        Map<String, Object> payload
) implements MessagePayload {
    public EventEnvelope {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
