package com.springtime.bigquery.error;

/** Thrown when a request is syntactically usable but not allowed by the service policy. */
public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
