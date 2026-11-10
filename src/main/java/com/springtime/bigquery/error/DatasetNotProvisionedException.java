package com.springtime.bigquery.error;

/**
 * The owned dataset or a table is missing. This is an operator action (apply the schema), not a
 * transient outage, so clients must not retry the same request.
 */
public class DatasetNotProvisionedException extends RuntimeException {

    public DatasetNotProvisionedException(String message) {
        super(message);
    }
}
