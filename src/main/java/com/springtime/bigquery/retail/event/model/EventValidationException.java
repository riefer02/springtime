package com.springtime.bigquery.retail.event.model;

/** A validation failure that belongs in the dead-letter path, not the retry loop. */
public class EventValidationException extends RuntimeException {

    public EventValidationException(String message) {
        super(message);
    }

}
