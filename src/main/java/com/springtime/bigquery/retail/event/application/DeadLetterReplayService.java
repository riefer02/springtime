package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.messaging.MessagePublisher;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.event.dto.DeadLetterView;
import com.springtime.bigquery.retail.event.dto.ReplayResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Lists durable dead letters and republishes one original event for reprocessing. */
@Service
public class DeadLetterReplayService {

    private static final int MAX_LIST_SIZE = 200;

    private final DeadLetterStore store;
    private final MessagePublisher publisher;
    private final MessagingProperties properties;
    private final Clock clock;

    public DeadLetterReplayService(DeadLetterStore store,
                                   MessagePublisher publisher,
                                   MessagingProperties properties,
                                   Clock clock) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    public PageResponse<DeadLetterView> list(String pageToken, int limit) {
        int pageSize = Math.min(Math.max(1, limit), MAX_LIST_SIZE);
        int offset = PageToken.decodeOffset(pageToken);
        List<DeadLetterSummary> fetched = store.list(pageSize + 1, offset);
        return PageToken.page(fetched.stream().map(DeadLetterReplayService::toView).toList(),
                offset, pageSize);
    }

    public ReplayResult replay(String eventId) {
        StoredDeadLetter stored = store.find(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Dead letter " + eventId + " was not found"));
        String messageId = publisher.publish(properties.eventsTopic(), stored.envelope().event());
        Instant replayedAt = clock.instant();
        store.markReplayed(eventId, replayedAt);
        return ReplayResult.replayed(eventId, messageId, replayedAt);
    }

    private static DeadLetterView toView(DeadLetterSummary summary) {
        return new DeadLetterView(
                summary.eventId(),
                summary.eventType(),
                summary.schemaVersion(),
                summary.failedAt(),
                summary.attempts(),
                summary.reason(),
                summary.errorType(),
                summary.originalTopic(),
                summary.replayedAt(),
                summary.replayStatus());
    }
}
