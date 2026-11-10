package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.InMemoryMessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.store.InMemoryDeadLetterStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingDeadLetterPublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void writesAStructuredEnvelopeWithFailureContext() {
        MessagingProperties properties = new MessagingProperties(
                false, "retail-events", "retail-events-dlq",
                "retail-events-worker", true, true, 500, 100);
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(properties);
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        MessagingDeadLetterPublisher deadLetterPublisher = new MessagingDeadLetterPublisher(
                publisher, properties, store, Clock.fixed(NOW, ZoneOffset.UTC));
        EventEnvelope event = new EventEnvelope(
                "evt-1", "SALE", NOW.minusSeconds(60), "1",
                "ST-001", "SKU-0001", 2L, Map.of());

        deadLetterPublisher.publish(event, "RETRY_EXHAUSTED", 5, new IllegalStateException("down"));

        assertThat(store.list(10, 0)).singleElement().satisfies(stored -> {
            assertThat(stored.replayStatus()).isEqualTo(StoredDeadLetter.PENDING);
            assertThat(stored.eventId()).isEqualTo("evt-1");
        });
        assertThat(publisher.deadLetters()).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo("retail-events-dlq");
            assertThat(message.payload()).isInstanceOfSatisfying(DeadLetterEnvelope.class, envelope -> {
                assertThat(envelope.originalEventId()).isEqualTo("evt-1");
                assertThat(envelope.attempts()).isEqualTo(5);
                assertThat(envelope.reason()).isEqualTo("RETRY_EXHAUSTED");
                assertThat(envelope.errorType()).isEqualTo("IllegalStateException");
                assertThat(envelope.originalTopic()).isEqualTo("retail-events");
                assertThat(envelope.failedAt()).isEqualTo(NOW);
                assertThat(envelope.event()).isEqualTo(event);
            });
        });
    }

    @Test
    void synthesisesAnIdForAPoisonEnvelope() {
        MessagingProperties properties = new MessagingProperties(
                false, "retail-events", "retail-events-dlq",
                "retail-events-worker", true, true, 500, 100);
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(properties);
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties);
        MessagingDeadLetterPublisher deadLetterPublisher = new MessagingDeadLetterPublisher(
                publisher, properties, store, Clock.fixed(NOW, ZoneOffset.UTC));
        EventEnvelope poison = new EventEnvelope(
                null, null, null, null, null, null, null, Map.of());

        deadLetterPublisher.publish(poison, "INVALID_EVENT", 0, new IllegalArgumentException("bad"));

        assertThat(publisher.deadLetters()).singleElement().satisfies(message ->
                assertThat(message.payload()).isInstanceOfSatisfying(DeadLetterEnvelope.class, envelope -> {
                    assertThat(envelope.originalEventId()).startsWith("missing-");
                    assertThat(envelope.eventType()).isEqualTo("UNKNOWN");
                    assertThat(envelope.schemaVersion()).isEqualTo("unknown");
                }));
    }
}
