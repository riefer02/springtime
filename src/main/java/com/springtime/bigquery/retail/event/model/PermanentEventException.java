package com.springtime.bigquery.retail.event.model;

/** A permanent event failure: retrying the same payload will not help. */
public class PermanentEventException extends RuntimeException {

    private final String reason;

    public PermanentEventException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
