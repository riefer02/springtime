package com.springtime.bigquery.retail.purchase.model;

/** Purchase order lifecycle. CANCELLED and SUBMITTED are terminal for this slice. */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    CANCELLED
}
