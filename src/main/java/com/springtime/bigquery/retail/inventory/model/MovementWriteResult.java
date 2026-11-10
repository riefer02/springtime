package com.springtime.bigquery.retail.inventory.model;

/**
 * Outcome of an idempotent ledger write.
 *
 * <p>{@code INSERTED} and {@code DUPLICATE} are exact for a single-movement write. In batch mode
 * the sink can only see an affected-row count, so every caller in a mixed batch is reported as
 * {@code INSERTED}; see {@link com.springtime.bigquery.retail.inventory.application
 * .InventoryMovementBatchSink#writeBatch}.</p>
 */
public enum MovementWriteResult {
    INSERTED,
    DUPLICATE
}
