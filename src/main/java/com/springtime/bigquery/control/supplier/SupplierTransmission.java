package com.springtime.bigquery.control.supplier;

import java.time.Instant;
import java.util.UUID;

/** Durable outbox row for one purchase-order transmission. */
public record SupplierTransmission(
        UUID transmissionId,
        String poId,
        String supplierId,
        SupplierTransmissionStatus status,
        int attempts,
        Instant nextAttemptAt,
        String payload,
        String payloadHash,
        String supplierReference,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        Instant ackedAt,
        long version
) {
}
