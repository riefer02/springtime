package com.springtime.bigquery.retail.inventory.model;

/** The kind of movement written to the inventory ledger. Quantities are signed on the movement. */
public enum InventoryMovementType {
    SALE,
    RETURN,
    ADJUSTMENT,
    SHIPMENT,
    OPENING_BALANCE
}
