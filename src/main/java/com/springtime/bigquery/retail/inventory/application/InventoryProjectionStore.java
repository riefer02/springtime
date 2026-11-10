package com.springtime.bigquery.retail.inventory.application;

import java.time.LocalDate;

/** Persistence port for the on-hand projection refresh. */
public interface InventoryProjectionStore {

    long rebuild();

    long mergeRange(LocalDate from, LocalDate to);
}
