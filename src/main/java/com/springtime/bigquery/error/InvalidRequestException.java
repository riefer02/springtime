package com.springtime.bigquery.error;

/**
 * Thrown when a syntactically present request parameter cannot be used by the service.
 * Maps to {@code 400 INVALID_REQUEST}; messages must never echo request input.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
