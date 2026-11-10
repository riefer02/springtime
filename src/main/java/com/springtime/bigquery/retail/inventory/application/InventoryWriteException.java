package com.springtime.bigquery.retail.inventory.application;

/** Failure from the inventory ledger adapter, classified for the retry loop. */
public class InventoryWriteException extends RuntimeException {

    private final boolean retryable;
    private final String reason;

    public InventoryWriteException(String reason, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    public String reason() {
        return reason;
    }
}
