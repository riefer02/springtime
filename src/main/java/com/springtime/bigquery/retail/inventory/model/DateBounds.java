package com.springtime.bigquery.retail.inventory.model;

import java.time.LocalDate;

/** Inclusive date bounds for ledger or projection data. Null when the source is empty. */
public record DateBounds(LocalDate earliest, LocalDate latest) {

    public static DateBounds empty() {
        return new DateBounds(null, null);
    }

    public boolean isEmpty() {
        return earliest == null || latest == null;
    }
}
