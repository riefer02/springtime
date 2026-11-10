package com.springtime.bigquery.retail.inventory.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable, signed inventory movement. On-hand is a projection over these rows, never a field
 * that is updated in place. {@code eventId} is the idempotency key for the ledger.
 */
public record InventoryMovement(
        String eventId,
        InventoryMovementType movementType,
        Instant occurredAt,
        LocalDate businessDate,
        String storeId,
        String productId,
        long quantityDelta,
        BigDecimal unitCost,
        String currency,
        String sourceSystem,
        String sourceDocumentId,
        String schemaVersion,
        Map<String, Object> payload
) {
    public InventoryMovement {
        eventId = require(eventId, "eventId");
        movementType = Objects.requireNonNull(movementType, "movementType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        businessDate = Objects.requireNonNull(businessDate, "businessDate");
        storeId = require(storeId, "storeId");
        productId = require(productId, "productId");
        currency = require(currency, "currency");
        sourceSystem = require(sourceSystem, "sourceSystem");
        sourceDocumentId = require(sourceDocumentId, "sourceDocumentId");
        schemaVersion = require(schemaVersion, "schemaVersion");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
