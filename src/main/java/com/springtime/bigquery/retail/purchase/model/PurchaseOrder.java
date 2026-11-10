package com.springtime.bigquery.retail.purchase.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Purchase order header created from approved proposals. */
public record PurchaseOrder(
        String poId,
        String poNumber,
        String idempotencyKey,
        String runId,
        String storeId,
        String supplierId,
        String supplierName,
        PurchaseOrderStatus status,
        String currency,
        BigDecimal totalValue,
        int lineCount,
        String requestedBy,
        String note,
        LocalDate expectedDeliveryDate,
        String supplierReference,
        int version,
        Instant createdAt,
        Instant submittedAt,
        Instant cancelledAt
) {
}
