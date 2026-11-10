package com.springtime.bigquery.retail.purchase.model;

import java.math.BigDecimal;

/** A purchase-order line ready to persist, independent of the replenishment model. */
public record PurchaseOrderLineDraft(
        String productId,
        String sku,
        String productName,
        String supplierId,
        long orderedUnits,
        BigDecimal lineValue,
        int sourceProposalVersion
) {
}
