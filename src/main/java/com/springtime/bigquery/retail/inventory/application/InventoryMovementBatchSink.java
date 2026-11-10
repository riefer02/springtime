package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.retail.inventory.model.InventoryMovement;

import java.util.List;

/** Batch write port for the inventory movement ledger. */
public interface InventoryMovementBatchSink extends InventoryMovementSink {

    /**
     * Write all movements in one idempotent BigQuery statement.
     *
     * <p>The result is batch-level: BigQuery reports affected rows, not which event ids inserted.
     * Callers therefore see {@code INSERTED} for every movement when at least one row inserted,
     * and {@code DUPLICATE} only when the whole batch was already present. The ledger still
     * dedupes by event id; per-event duplicate accounting requires single writes.</p>
     *
     * @return the number of rows inserted; duplicates are not counted
     */
    long writeBatch(List<InventoryMovement> movements);
}
