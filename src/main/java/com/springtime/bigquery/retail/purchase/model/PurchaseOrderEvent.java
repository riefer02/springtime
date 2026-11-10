package com.springtime.bigquery.retail.purchase.model;

import java.time.Instant;

/** Append-only purchase order lifecycle event. */
public record PurchaseOrderEvent(
        String eventId,
        String poId,
        PurchaseOrderStatus fromStatus,
        PurchaseOrderStatus toStatus,
        String actor,
        String note,
        Instant occurredAt
) {
}
