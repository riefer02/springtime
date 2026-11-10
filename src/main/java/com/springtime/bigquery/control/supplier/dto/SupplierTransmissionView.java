package com.springtime.bigquery.control.supplier.dto;

import java.time.Instant;
import java.util.UUID;

/** API view of a supplier transmission; the payload is deliberately not exposed. */
public record SupplierTransmissionView(
        UUID transmissionId,
        String poId,
        String supplierId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String supplierReference,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt,
        Instant ackedAt,
        long version
) {
}
