package com.springtime.bigquery.error;

/** Thrown when a requested dataset or table does not exist. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
