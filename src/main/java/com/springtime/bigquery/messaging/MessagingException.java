package com.springtime.bigquery.messaging;

public class MessagingException extends RuntimeException {

    public MessagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessagingException(String message) {
        super(message);
    }
}
