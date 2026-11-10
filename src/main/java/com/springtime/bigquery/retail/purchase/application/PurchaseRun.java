package com.springtime.bigquery.retail.purchase.application;

/** Purchase-owned snapshot of the replenishment run needed to validate PO creation. */
public record PurchaseRun(String runId, String storeId, boolean completed) {
}
