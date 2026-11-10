package com.springtime.bigquery.retail.event.dto;

import java.time.Instant;

/** Result of republishing one dead letter. */
public record ReplayResult(
        String eventId,
        String messageId,
        Instant replayedAt,
        String status
) {
    public static ReplayResult replayed(String eventId, String messageId, Instant replayedAt) {
        return new ReplayResult(eventId, messageId, replayedAt, "REPLAYED");
    }
}
