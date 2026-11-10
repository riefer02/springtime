package com.springtime.bigquery.retail.event.store;

import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.application.StoredDeadLetter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDeadLetterStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void recordsListsFindsAndMarksReplayed() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties(10));
        store.record(envelope("evt-1", 3));

        assertThat(store.list(1, 0)).singleElement().satisfies(stored -> {
            assertThat(stored.eventId()).isEqualTo("evt-1");
            assertThat(stored.replayStatus()).isEqualTo(StoredDeadLetter.PENDING);
        });
        assertThat(store.list(1, 1)).isEmpty();
        assertThat(store.find("evt-1")).isPresent();

        store.markReplayed("evt-1", NOW.plusSeconds(10));

        assertThat(store.find("evt-1")).get().satisfies(stored -> {
            assertThat(stored.replayStatus()).isEqualTo(StoredDeadLetter.REPLAYED);
            assertThat(stored.replayedAt()).isEqualTo(NOW.plusSeconds(10));
        });
    }

    @Test
    void duplicateRecordRefreshesTheFailureState() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties(10));
        store.record(envelope("evt-1", 3));
        store.record(envelope("evt-1", 5));

        assertThat(store.list(10, 0)).singleElement().satisfies(stored -> {
            assertThat(stored.attempts()).isEqualTo(5);
            assertThat(stored.replayStatus()).isEqualTo(StoredDeadLetter.PENDING);
        });
    }

    @Test
    void failsClosedWhenFull() {
        InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(properties(1));
        store.record(envelope("evt-1", 3));

        assertThatThrownBy(() -> store.record(envelope("evt-2", 3)))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("full");
    }

    private static MessagingProperties properties(int max) {
        return new MessagingProperties(false, "retail-events", "retail-events-dlq",
                "retail-events-worker", true, true, 500, max);
    }

    private static DeadLetterEnvelope envelope(String eventId, int attempts) {
        EventEnvelope event = new EventEnvelope(
                eventId, "SALE", NOW.minusSeconds(60), "1",
                "ST-001", "SKU-0001", 2L, Map.of());
        return new DeadLetterEnvelope(
                eventId, "SALE", "1", NOW, attempts,
                "RETRY_EXHAUSTED", "IllegalStateException", "retail-events", event);
    }
}
