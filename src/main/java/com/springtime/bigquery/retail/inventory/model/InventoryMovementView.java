package com.springtime.bigquery.retail.inventory.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Read model for a ledger row returned by the inventory movement endpoint. */
public record InventoryMovementView(
        String eventId,
        String movementType,
        Instant occurredAt,
        LocalDate businessDate,
        String storeId,
        String productId,
        long quantityDelta,
        BigDecimal unitCost,
        String currency,
        String sourceSystem,
        String sourceDocumentId,
        String schemaVersion
) {
}
