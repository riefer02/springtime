package com.springtime.bigquery.error;

/**
 * The request is well-formed and authorised, but a business rule forbids it (for example "only a
 * FAILED transmission can be retried"). Retrying the same request cannot succeed; the client must
 * change the request or the underlying state.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
