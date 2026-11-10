package com.springtime.bigquery.control.supplier;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable outbox storage boundary. */
public interface SupplierTransmissionRepository {

    SupplierTransmission enqueue(SupplierTransmission transmission);

    Optional<SupplierTransmission> find(UUID transmissionId);

    Optional<SupplierTransmission> findByPoId(String poId);

    List<SupplierTransmission> list(SupplierTransmissionStatus status, int limit, int offset);

    List<SupplierTransmission> claimDue(int limit);

    /** Returns the number of rows moved; zero means the claim was superseded by another worker. */
    int markSent(UUID transmissionId, long expectedVersion, String supplierReference, Instant sentAt);

    int markRetry(UUID transmissionId, long expectedVersion, String error, Instant nextAttemptAt);

    int markFailed(UUID transmissionId, long expectedVersion, String error);

    int markAcked(UUID transmissionId, long expectedVersion, String supplierReference,
                  Instant ackedAt, String actor);

    int retry(UUID transmissionId, long expectedVersion, Instant nextAttemptAt, String actor);
}
