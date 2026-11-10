package com.springtime.bigquery.control.supplier;

/**
 * Signals a retryable supplier transport failure (timeout, throttling, temporary upstream error).
 *
 * <p>Adapters should use this for known-transient failures; other runtime exceptions are also
 * treated as transient by the worker.</p>
 */
public class SupplierTransientException extends RuntimeException {

    public SupplierTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
