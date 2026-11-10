package com.springtime.bigquery.messaging;

/**
 * The minimal contract a broker payload must expose. Keeping it narrow lets the broker carry both
 * normal events and dead-letter envelopes without knowing retail domain types.
 */
public interface MessagePayload {

    String eventId();

    String eventType();

    String schemaVersion();
}
