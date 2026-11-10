package com.springtime.bigquery.model.query;

import java.time.Instant;

/** Summary of a table inside a dataset. */
public record TableSummary(
        String tableId,
        String type,
        Instant created
) {
}
