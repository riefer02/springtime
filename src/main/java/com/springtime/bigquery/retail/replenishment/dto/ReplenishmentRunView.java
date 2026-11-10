package com.springtime.bigquery.retail.replenishment.dto;

import java.time.Instant;
import java.time.LocalDate;

/** API view of a replenishment run. */
public record ReplenishmentRunView(
        String runId,
        String storeId,
        LocalDate asOfDate,
        String status,
        int demandWindowDays,
        int targetCoverDays,
        String requestedBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        Integer proposalCount,
        String totalOrderValue,
        String errorMessage
) {
}
