package com.springtime.bigquery.error;

/** Retail data has not been loaded for the requested scope yet. */
public class DataNotReadyException extends RuntimeException {

    public DataNotReadyException(String message) {
        super(message);
    }
}
