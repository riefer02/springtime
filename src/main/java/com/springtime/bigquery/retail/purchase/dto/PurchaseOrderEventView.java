package com.springtime.bigquery.retail.purchase.dto;

import java.time.Instant;

/** API view of one purchase order lifecycle event. */
public record PurchaseOrderEventView(
        String eventId,
        String poId,
        String fromStatus,
        String toStatus,
        String actor,
        String note,
        Instant occurredAt
) {
}
