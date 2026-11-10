package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.MessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Writes a structured dead-letter envelope to the configured dead-letter topic. */
@Component
public class MessagingDeadLetterPublisher implements DeadLetterPublisher {

    private final MessagePublisher publisher;
    private final MessagingProperties properties;
    private final DeadLetterStore store;
    private final Clock clock;

    public MessagingDeadLetterPublisher(MessagePublisher publisher,
                                        MessagingProperties properties,
                                        DeadLetterStore store,
                                        Clock clock) {
        this.publisher = publisher;
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void publish(EventEnvelope event, String reason, int attempts, Throwable cause) {
        String originalEventId = event.eventId() == null || event.eventId().isBlank()
                ? "missing-" + UUID.randomUUID()
                : event.eventId();
        DeadLetterEnvelope envelope = new DeadLetterEnvelope(
                originalEventId,
                event.eventType() == null ? "UNKNOWN" : event.eventType(),
                event.schemaVersion() == null ? "unknown" : event.schemaVersion(),
                clock.instant(),
                attempts,
                reason,
                cause == null ? null : cause.getClass().getSimpleName(),
                properties.eventsTopic(),
                event);
        store.record(envelope);
        publisher.publish(properties.deadLetterTopic(), envelope);
    }
}
