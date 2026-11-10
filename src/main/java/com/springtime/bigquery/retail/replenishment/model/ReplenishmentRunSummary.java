package com.springtime.bigquery.retail.replenishment.model;

import java.math.BigDecimal;

/** Aggregated result of a run. */
public record ReplenishmentRunSummary(
        int proposalCount,
        BigDecimal totalOrderValue
) {
    public static ReplenishmentRunSummary empty() {
        return new ReplenishmentRunSummary(0, BigDecimal.ZERO);
    }
}
