package com.springtime.bigquery.error;

/** Thrown when a request exceeds the service's concurrency/rate budget. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
