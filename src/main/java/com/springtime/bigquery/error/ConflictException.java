package com.springtime.bigquery.error;

/** The request lost an optimistic-concurrency or state-transition race. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
