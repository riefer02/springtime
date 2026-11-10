package com.springtime.bigquery.messaging;

public class RetryInterruptedException extends RuntimeException {

    public RetryInterruptedException(Throwable cause) {
        super("Interrupted while waiting to retry", cause);
    }
}
