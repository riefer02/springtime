package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;

/** Idempotent write port for the inventory movement ledger. */
public interface InventoryMovementSink {

    MovementWriteResult write(InventoryMovement movement);
}
