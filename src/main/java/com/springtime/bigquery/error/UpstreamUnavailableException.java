package com.springtime.bigquery.error;

/** Thrown when an upstream dependency is temporarily unavailable. */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
