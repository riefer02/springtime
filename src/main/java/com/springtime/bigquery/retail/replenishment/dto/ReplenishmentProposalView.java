package com.springtime.bigquery.retail.replenishment.dto;

import java.time.Instant;
import java.time.LocalDate;

/** API view of one replenishment proposal and its decision state. */
public record ReplenishmentProposalView(
        String runId,
        LocalDate asOfDate,
        String storeId,
        String productId,
        String sku,
        String productName,
        String category,
        String supplierId,
        String supplierName,
        long availableUnits,
        double avgDailyUnits,
        Double daysOfSupply,
        long leadTimeDays,
        long effectiveReorderPoint,
        long recommendedUnits,
        String orderValue,
        String reason,
        String status,
        int version,
        Instant decidedAt,
        String decidedBy,
        String decisionNote,
        String orderReference
) {
}
