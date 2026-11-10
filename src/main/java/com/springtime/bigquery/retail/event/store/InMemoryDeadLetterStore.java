package com.springtime.bigquery.retail.event.store;

import com.springtime.bigquery.retail.event.application.DeadLetterSummary;
import com.springtime.bigquery.retail.event.application.StoredDeadLetter;
import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.application.DeadLetterStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local dead-letter store. Bounded and non-durable; production uses BigQuery. */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryDeadLetterStore implements DeadLetterStore {

    private final ConcurrentMap<String, StoredDeadLetter> entries = new ConcurrentHashMap<>();
    private final int maxEntries;

    public InMemoryDeadLetterStore(MessagingProperties properties) {
        this.maxEntries = properties.maxInMemoryMessages();
    }

    @Override
    public void record(DeadLetterEnvelope envelope) {
        if (!entries.containsKey(envelope.eventId()) && entries.size() >= maxEntries) {
            throw new MessagingException("Local dead-letter store is full; refusing to drop "
                    + envelope.eventId());
        }
        entries.put(envelope.eventId(), StoredDeadLetter.pending(envelope));
    }

    @Override
    public List<DeadLetterSummary> list(int limit, int offset) {
        return entries.values().stream()
                .sorted(Comparator.comparing(
                        (StoredDeadLetter stored) -> stored.envelope().failedAt()).reversed())
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .map(InMemoryDeadLetterStore::summary)
                .toList();
    }

    private static DeadLetterSummary summary(StoredDeadLetter stored) {
        var envelope = stored.envelope();
        return new DeadLetterSummary(
                envelope.originalEventId(), envelope.eventType(), envelope.schemaVersion(),
                envelope.failedAt(), envelope.attempts(), envelope.reason(), envelope.errorType(),
                envelope.originalTopic(), stored.replayedAt(), stored.replayStatus());
    }

    @Override
    public Optional<StoredDeadLetter> find(String eventId) {
        return Optional.ofNullable(entries.get(eventId));
    }

    @Override
    public void markReplayed(String eventId, Instant replayedAt) {
        entries.computeIfPresent(eventId,
                (key, stored) -> stored.replayedAt(replayedAt));
    }

}
