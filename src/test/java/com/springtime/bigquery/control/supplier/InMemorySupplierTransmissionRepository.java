package com.springtime.bigquery.control.supplier;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-only in-memory outbox. Runtime always uses Postgres; this exists so unit tests can exercise
 * orchestration without a database.
 */
public class InMemorySupplierTransmissionRepository implements SupplierTransmissionRepository {

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentMap<UUID, SupplierTransmission> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UUID> byPoId = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemorySupplierTransmissionRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized SupplierTransmission enqueue(SupplierTransmission transmission) {
        UUID existing = byPoId.get(transmission.poId());
        if (existing != null) {
            return byId.get(existing);
        }
        if (byId.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("In-memory supplier outbox is full");
        }
        byId.put(transmission.transmissionId(), transmission);
        byPoId.put(transmission.poId(), transmission.transmissionId());
        return transmission;
    }

    @Override
    public Optional<SupplierTransmission> find(UUID transmissionId) {
        return Optional.ofNullable(byId.get(transmissionId));
    }

    @Override
    public Optional<SupplierTransmission> findByPoId(String poId) {
        UUID id = byPoId.get(poId);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<SupplierTransmission> list(SupplierTransmissionStatus status, int limit, int offset) {
        return byId.values().stream()
                .filter(value -> status == null || value.status() == status)
                .sorted(Comparator.comparing(SupplierTransmission::createdAt).reversed()
                        .thenComparing(value -> value.transmissionId().toString()))
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized List<SupplierTransmission> claimDue(int limit) {
        Instant now = clock.instant();
        return byId.values().stream()
                .filter(value -> value.status() == SupplierTransmissionStatus.PENDING)
                .filter(value -> !value.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(SupplierTransmission::nextAttemptAt))
                .limit(Math.max(1, limit))
                .map(value -> replace(value.transmissionId(),
                        new SupplierTransmission(
                                value.transmissionId(), value.poId(), value.supplierId(),
                                SupplierTransmissionStatus.SENDING, value.attempts() + 1,
                                value.nextAttemptAt(), value.payload(), value.payloadHash(),
                                value.supplierReference(), value.lastError(), value.createdAt(),
                                now, value.sentAt(), value.ackedAt(), value.version() + 1)))
                .toList();
    }

    @Override
    public synchronized int markSent(UUID transmissionId, long expectedVersion,
                                     String supplierReference, Instant sentAt) {
        SupplierTransmission value = byId.get(transmissionId);
        if (value == null || value.status() != SupplierTransmissionStatus.SENDING
                || value.version() != expectedVersion) {
            return 0;
        }
        replace(transmissionId, new SupplierTransmission(
                value.transmissionId(), value.poId(), value.supplierId(),
                SupplierTransmissionStatus.SENT, value.attempts(), value.nextAttemptAt(),
                value.payload(), value.payloadHash(), supplierReference, value.lastError(),
                value.createdAt(), clock.instant(), sentAt, value.ackedAt(), value.version() + 1));
        return 1;
    }

    @Override
    public synchronized int markRetry(UUID transmissionId, long expectedVersion,
                                      String error, Instant nextAttemptAt) {
        SupplierTransmission value = byId.get(transmissionId);
        if (value == null || value.status() != SupplierTransmissionStatus.SENDING
                || value.version() != expectedVersion) {
            return 0;
        }
        replace(transmissionId, new SupplierTransmission(
                value.transmissionId(), value.poId(), value.supplierId(),
                SupplierTransmissionStatus.PENDING, value.attempts(), nextAttemptAt,
                value.payload(), value.payloadHash(), value.supplierReference(), error,
                value.createdAt(), clock.instant(), value.sentAt(), value.ackedAt(),
                value.version() + 1));
        return 1;
    }

    @Override
    public synchronized int markFailed(UUID transmissionId, long expectedVersion, String error) {
        SupplierTransmission value = byId.get(transmissionId);
        if (value == null || value.status() != SupplierTransmissionStatus.SENDING
                || value.version() != expectedVersion) {
            return 0;
        }
        replace(transmissionId, new SupplierTransmission(
                value.transmissionId(), value.poId(), value.supplierId(),
                SupplierTransmissionStatus.FAILED, value.attempts(), value.nextAttemptAt(),
                value.payload(), value.payloadHash(), value.supplierReference(), error,
                value.createdAt(), clock.instant(), value.sentAt(), value.ackedAt(),
                value.version() + 1));
        return 1;
    }

    @Override
    public synchronized int markAcked(UUID transmissionId, long expectedVersion,
                                      String supplierReference, Instant ackedAt, String actor) {
        SupplierTransmission value = byId.get(transmissionId);
        if (value == null || value.status() != SupplierTransmissionStatus.SENT
                || value.version() != expectedVersion) {
            return 0;
        }
        replace(transmissionId, new SupplierTransmission(
                value.transmissionId(), value.poId(), value.supplierId(),
                SupplierTransmissionStatus.ACKED, value.attempts(), value.nextAttemptAt(),
                value.payload(), value.payloadHash(),
                supplierReference == null ? value.supplierReference() : supplierReference,
                value.lastError(), value.createdAt(), clock.instant(), value.sentAt(),
                ackedAt, value.version() + 1));
        return 1;
    }

    @Override
    public synchronized int retry(UUID transmissionId, long expectedVersion,
                                  Instant nextAttemptAt, String actor) {
        SupplierTransmission value = byId.get(transmissionId);
        if (value == null || value.status() != SupplierTransmissionStatus.FAILED
                || value.version() != expectedVersion) {
            return 0;
        }
        replace(transmissionId, new SupplierTransmission(
                value.transmissionId(), value.poId(), value.supplierId(),
                SupplierTransmissionStatus.PENDING, 0, nextAttemptAt,
                value.payload(), value.payloadHash(), value.supplierReference(), null,
                value.createdAt(), clock.instant(), value.sentAt(), value.ackedAt(),
                value.version() + 1));
        return 1;
    }

    private SupplierTransmission replace(UUID id, SupplierTransmission replacement) {
        byId.put(id, replacement);
        return replacement;
    }

}
