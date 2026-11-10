package com.springtime.bigquery.retail.inventory.model;

import java.time.Instant;
import java.time.LocalDate;

/** Result of a projection refresh. */
public record ProjectionResult(
        String status,
        LocalDate fromDate,
        LocalDate toDate,
        long affectedRows,
        Instant projectedAt
) {
}
