package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.InMemoryMessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.dto.DeadLetterView;
import com.springtime.bigquery.retail.event.dto.ReplayResult;
import com.springtime.bigquery.retail.event.store.InMemoryDeadLetterStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadLetterReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final MessagingProperties PROPERTIES = new MessagingProperties(
            false, "retail-events", "retail-events-dlq",
            "retail-events-worker", true, true, 500, 100);

    private final InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(PROPERTIES);
    private final InMemoryDeadLetterStore store = new InMemoryDeadLetterStore(PROPERTIES);
    private final DeadLetterReplayService service = new DeadLetterReplayService(
            store, publisher, PROPERTIES, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void listsDeadLetters() {
        store.record(envelope("evt-1"));

        var page = service.list(null, 10);

        assertThat(page.items()).singleElement().satisfies(view -> {
            assertThat(view.eventId()).isEqualTo("evt-1");
            assertThat(view.reason()).isEqualTo("RETRY_EXHAUSTED");
            assertThat(view.replayStatus()).isEqualTo(StoredDeadLetter.PENDING);
        });
        assertThat(page.pageSize()).isEqualTo(10);
        assertThat(page.nextPageToken()).isNull();
    }

    @Test
    void pagesDeadLettersWithAnOpaqueToken() {
        store.record(envelope("evt-1", NOW.minusSeconds(3)));
        store.record(envelope("evt-2", NOW.minusSeconds(2)));
        store.record(envelope("evt-3", NOW.minusSeconds(1)));

        var first = service.list(null, 2);

        assertThat(first.items()).extracting(DeadLetterView::eventId)
                .containsExactly("evt-3", "evt-2");
        assertThat(first.nextPageToken()).isNotNull();

        var second = service.list(first.nextPageToken(), 2);

        assertThat(second.items()).extracting(DeadLetterView::eventId).containsExactly("evt-1");
        assertThat(second.nextPageToken()).isNull();
    }

    @Test
    void rejectsAMalformedPageToken() {
        assertThatThrownBy(() -> service.list("not-a-token", 10))
                .isInstanceOf(com.springtime.bigquery.error.InvalidRequestException.class);
    }

    @Test
    void replaysTheOriginalEventAndMarksItReplayed() {
        store.record(envelope("evt-1"));

        ReplayResult result = service.replay("evt-1");

        assertThat(result.eventId()).isEqualTo("evt-1");
        assertThat(result.status()).isEqualTo("REPLAYED");
        assertThat(publisher.publishedEvents()).singleElement().satisfies(message -> {
            assertThat(message.topic()).isEqualTo("retail-events");
            assertThat(message.payload()).isInstanceOf(EventEnvelope.class);
            assertThat(message.payload().eventId()).isEqualTo("evt-1");
        });
        assertThat(store.find("evt-1")).get()
                .satisfies(stored -> assertThat(stored.replayStatus())
                        .isEqualTo(StoredDeadLetter.REPLAYED));
    }

    @Test
    void rejectsAnUnknownDeadLetter() {
        assertThatThrownBy(() -> service.replay("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static DeadLetterEnvelope envelope(String eventId) {
        return envelope(eventId, NOW);
    }

    private static DeadLetterEnvelope envelope(String eventId, Instant failedAt) {
        EventEnvelope event = new EventEnvelope(
                eventId, "SALE", NOW.minusSeconds(60), "1",
                "ST-001", "SKU-0001", 2L, Map.of());        return new DeadLetterEnvelope(
                eventId, "SALE", "1", failedAt, 3,
                "RETRY_EXHAUSTED", "IllegalStateException", "retail-events", event);
    }
}
