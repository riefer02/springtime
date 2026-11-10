package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.EventEnvelope;

/** Publishes a failed event with enough context to inspect, replay, or discard it. */
public interface DeadLetterPublisher {

    void publish(EventEnvelope event, String reason, int attempts, Throwable cause);
}
