package com.springtime.bigquery.retail.replenishment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Durable metadata for one replenishment decision run. */
public record ReplenishmentRun(
        String runId,
        String storeId,
        LocalDate asOfDate,
        ReplenishmentRunStatus status,
        int demandWindowDays,
        int targetCoverDays,
        String requestedBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Integer proposalCount,
        BigDecimal totalOrderValue,
        String errorMessage
) {
}
