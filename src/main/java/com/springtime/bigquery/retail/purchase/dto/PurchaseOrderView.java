package com.springtime.bigquery.retail.purchase.dto;

import java.time.Instant;
import java.time.LocalDate;

/** API view of a purchase order. */
public record PurchaseOrderView(
        String poId,
        String poNumber,
        String idempotencyKey,
        String runId,
        String storeId,
        String supplierId,
        String supplierName,
        String status,
        String currency,
        String totalValue,
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
