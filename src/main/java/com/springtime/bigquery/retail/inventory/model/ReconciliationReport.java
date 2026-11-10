package com.springtime.bigquery.retail.inventory.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Result of comparing the movement ledger with the inventory snapshot for one business date. */
public record ReconciliationReport(
        String status,
        LocalDate asOfDate,
        long tolerance,
        int driftPairs,
        int limit,
        boolean truncated,
        long maxAbsoluteDrift,
        Instant checkedAt,
        List<InventoryDrift> items
) {
    public ReconciliationReport {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
