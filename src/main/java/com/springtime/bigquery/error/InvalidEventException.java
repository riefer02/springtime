package com.springtime.bigquery.error;

/** A retail event failed semantic validation. This is a client error, not an upstream failure. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
