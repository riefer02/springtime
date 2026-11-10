package com.springtime.bigquery.retail.purchase.dto;

import java.time.Instant;

/** API view of one purchase order line. */
public record PurchaseOrderLineView(
        String poId,
        int lineNumber,
        String productId,
        String sku,
        String productName,
        String supplierId,
        long orderedUnits,
        String lineValue,
        Instant createdAt
) {
}
