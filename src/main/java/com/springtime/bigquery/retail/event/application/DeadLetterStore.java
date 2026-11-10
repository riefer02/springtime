package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable/public store for dead letters and their replay state. */
public interface DeadLetterStore {

    void record(DeadLetterEnvelope envelope);

    List<DeadLetterSummary> list(int limit, int offset);

    Optional<StoredDeadLetter> find(String eventId);

    void markReplayed(String eventId, Instant replayedAt);
}
