package com.springtime.bigquery.retail.replenishment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One prioritized order suggestion produced by a run. */
public record ReplenishmentProposal(
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
        BigDecimal orderValue,
        String reason,
        ProposalStatus status,
        int version,
        Instant decidedAt,
        String decidedBy,
        String decisionNote,
        String orderReference
) {
    public ReplenishmentProposal withDecision(ProposalStatus newStatus,
                                              int newVersion,
                                              Instant at,
                                              String by,
                                              String note,
                                              String orderRef) {
        return new ReplenishmentProposal(
                runId, asOfDate, storeId, productId, sku, productName, category,
                supplierId, supplierName, availableUnits, avgDailyUnits, daysOfSupply,
                leadTimeDays, effectiveReorderPoint, recommendedUnits, orderValue, reason,
                newStatus, newVersion, at, by, note, orderRef);
    }
}
