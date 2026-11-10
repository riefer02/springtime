package com.springtime.bigquery.messaging;

public class RetryExhaustedException extends RuntimeException {

    private final String operation;
    private final int attempts;

    public RetryExhaustedException(String operation, int attempts, Throwable cause) {
        super("Retry exhausted for " + operation + " after " + attempts + " attempt(s)", cause);
        this.operation = operation;
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
