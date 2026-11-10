package com.springtime.bigquery.retail.purchase.application;

import java.math.BigDecimal;

/** The purchase-order view of one approved replenishment proposal. */
public record ApprovedProposal(
        String productId,
        String sku,
        String productName,
        String supplierId,
        String supplierName,
        long recommendedUnits,
        BigDecimal orderValue,
        int version
) {
}
