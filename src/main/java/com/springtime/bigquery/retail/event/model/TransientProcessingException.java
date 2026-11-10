package com.springtime.bigquery.retail.event.model;

/** A transient processing failure: the broker should redeliver the message. */
public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

}
