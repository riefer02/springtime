package com.springtime.bigquery.retail.purchase.model;

import java.math.BigDecimal;
import java.time.Instant;

/** One purchase order line copied from an approved proposal. */
public record PurchaseOrderLine(
        String poId,
        int lineNumber,
        String productId,
        String sku,
        String productName,
        String supplierId,
        long orderedUnits,
        BigDecimal lineValue,
        int sourceProposalVersion,
        Instant createdAt
) {
}
