package com.springtime.bigquery.retail.inventory.model;

/** One store/product pair whose ledger balance does not match the inventory snapshot. */
public record InventoryDrift(
        String storeId,
        String productId,
        long ledgerUnits,
        long snapshotUnits,
        long driftUnits
) {
}
