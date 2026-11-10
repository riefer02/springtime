package com.springtime.bigquery.control.supplier;

import com.springtime.bigquery.control.TransactionRunner;
import com.springtime.bigquery.control.supplier.dto.SupplierTransmissionView;
import com.springtime.bigquery.error.BusinessRuleException;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.util.Backoff;
import com.springtime.bigquery.util.SafeLog;
import com.springtime.bigquery.util.Uuids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Durable outbox orchestration for supplier purchase-order transmission. */
@Service
public class SupplierTransmissionService implements SupplierOutbox {

    private static final Logger log = LoggerFactory.getLogger(SupplierTransmissionService.class);
    private static final int MAX_LIST = 200;

    private final SupplierPurchaseOrderReader purchaseOrderReader;
    private final SupplierTransmissionRepository transmissionRepository;
    private final SupplierGateway supplierGateway;
    private final SupplierOutboxProperties properties;
    private final JsonMapper jsonMapper;
    private final SupplierTransmissionMetrics metrics;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public SupplierTransmissionService(SupplierPurchaseOrderReader purchaseOrderReader,
                                       SupplierTransmissionRepository transmissionRepository,
                                       SupplierGateway supplierGateway,
                                       SupplierOutboxProperties properties,
                                       JsonMapper jsonMapper,
                                       SupplierTransmissionMetrics metrics,
                                       TransactionRunner transactionRunner,
                                       Clock clock) {
        this.purchaseOrderReader = purchaseOrderReader;
        this.transmissionRepository = transmissionRepository;
        this.supplierGateway = supplierGateway;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.metrics = metrics;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    @Override
    public void enqueue(String poId, String actor) {
        enqueueForPo(poId, actor);
    }

    /** Idempotently enqueue a submitted purchase order. */
    public SupplierTransmissionView enqueueForPo(String poId, String actor) {
        return transactionRunner.run(() -> enqueueInternal(poId, actor));
    }

    private SupplierTransmissionView enqueueInternal(String poId, String actor) {
        if (!Uuids.isValid(poId)) {
            throw new ResourceNotFoundException("Purchase order " + SafeLog.id(poId) + " was not found");
        }
        // Check for an existing row before loading a potentially large PO + line set.
        var existing = transmissionRepository.findByPoId(poId);
        if (existing.isPresent()) {
            return view(existing.get());
        }

        SupplierPurchaseOrder order = purchaseOrderReader.findForTransmission(poId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Purchase order " + poId + " was not found"));
        if (!order.submitted()) {
            throw new BusinessRuleException("Only a submitted purchase order can be transmitted");
        }

        Instant now = clock.instant();
        UUID transmissionId = UUID.randomUUID();
        String payload = payload(order, transmissionId);
        SupplierTransmission transmission = new SupplierTransmission(
                transmissionId, order.poId(), order.supplierId(),
                SupplierTransmissionStatus.PENDING, 0, now, payload, hash(payload),
                null, null, now, now, null, null, 0);
        SupplierTransmission stored = transmissionRepository.enqueue(transmission);
        metrics.record("enqueued");
        log.info("supplier transmission enqueued poId={} transmissionId={} actor={}",
                order.poId(), stored.transmissionId(), SafeLog.id(actor));
        return view(stored);
    }

    /** Worker entry point: claim a batch and attempt each transmission once. */
    public void processDue() {
        if (!properties.enabled()) {
            return;
        }
        List<SupplierTransmission> claimed =
                transmissionRepository.claimDue(properties.batchSize());
        for (SupplierTransmission transmission : claimed) {
            try {
                send(transmission);
            } catch (RuntimeException exception) {
                // One broken row must not abandon the rest of the claimed batch.
                metrics.record("worker_error");
                log.error("supplier transmission worker row failed transmissionId={} error={}",
                        transmission.transmissionId(), describe(exception));
            }
        }
    }

    public PageResponse<SupplierTransmissionView> list(SupplierTransmissionStatus status,
                                                        String pageToken, int limit) {
        int pageSize = Math.min(Math.max(1, limit), MAX_LIST);
        int offset = PageToken.decodeOffset(pageToken);
        List<SupplierTransmissionView> fetched =
                transmissionRepository.list(status, pageSize + 1, offset).stream()
                        .map(SupplierTransmissionService::view)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    public SupplierTransmissionView get(UUID transmissionId) {
        return view(require(transmissionId));
    }

    public SupplierTransmissionView retry(UUID transmissionId, String actor) {
        SupplierTransmission current = require(transmissionId);
        if (current.status() != SupplierTransmissionStatus.FAILED) {
            throw new BusinessRuleException("Only a FAILED transmission can be retried");
        }
        if (transmissionRepository.retry(
                transmissionId, current.version(), clock.instant(), actor) == 0) {
            throw new ConflictException("Transmission changed while retrying; reload and retry");
        }
        metrics.record("retried");
        log.info("supplier transmission retried transmissionId={} actor={}",
                transmissionId, SafeLog.id(actor));
        return get(transmissionId);
    }

    public SupplierTransmissionView acknowledge(UUID transmissionId, String supplierReference,
                                               String actor) {
        SupplierTransmission current = require(transmissionId);
        if (current.status() == SupplierTransmissionStatus.ACKED) {
            return view(current);
        }
        if (current.status() != SupplierTransmissionStatus.SENT) {
            throw new BusinessRuleException("Only a SENT transmission can be acknowledged");
        }
        if (transmissionRepository.markAcked(
                transmissionId, current.version(), supplierReference, clock.instant(), actor) == 0) {
            throw new ConflictException("Transmission changed while acknowledging; reload and retry");
        }
        metrics.record("acked");
        log.info("supplier transmission acknowledged transmissionId={} actor={}",
                transmissionId, SafeLog.id(actor));
        return get(transmissionId);
    }

    private void send(SupplierTransmission transmission) {
        SupplierSubmission submission;
        try {
            submission = supplierGateway.submit(transmission);
        } catch (SupplierTransientException exception) {
            handleTransient(transmission, exception);
            return;
        } catch (SupplierPermanentException exception) {
            if (transmissionRepository.markFailed(
                    transmission.transmissionId(), transmission.version(),
                    describe(exception)) == 0) {
                log.warn("stale supplier worker could not mark permanent failure transmissionId={}",
                        transmission.transmissionId());
                return;
            }
            metrics.record("failed");
            log.error("supplier transmission permanently failed transmissionId={} error={}",
                    transmission.transmissionId(), describe(exception));
            return;
        } catch (RuntimeException exception) {
            // An unclassified runtime failure is treated as transient: network and timeout errors
            // are the common case. Adapters should throw SupplierPermanentException for
            // deterministic rejections.
            handleTransient(transmission, exception);
            return;
        }

        try {
            int updated = transmissionRepository.markSent(
                    transmission.transmissionId(), transmission.version(),
                    submission.supplierReference(),
                    submission.acceptedAt() == null ? clock.instant() : submission.acceptedAt());
            if (updated == 0) {
                log.warn("supplier transmission accepted but the claim was superseded transmissionId={}",
                        transmission.transmissionId());
                return;
            }
            metrics.record("sent");
        } catch (RuntimeException exception) {
            // The supplier already accepted the order. Do not flip the row back to PENDING and
            // resend immediately; leave SENDING so lease recovery retries with the same
            // transmission id (the supplier-side idempotency key).
            log.error("supplier transmission accepted but markSent failed transmissionId={} error={}",
                    transmission.transmissionId(), describe(exception));
        }
    }

    private void handleTransient(SupplierTransmission transmission, RuntimeException exception) {
        if (transmission.attempts() >= properties.maxAttempts()) {
            if (transmissionRepository.markFailed(
                    transmission.transmissionId(), transmission.version(),
                    describe(exception)) == 0) {
                log.warn("stale supplier worker could not mark exhaustion transmissionId={}",
                        transmission.transmissionId());
                return;
            }
            metrics.record("failed");
            log.error("supplier transmission exhausted attempts transmissionId={} attempts={} error={}",
                    transmission.transmissionId(), transmission.attempts(), describe(exception));
            return;
        }
        Instant next = clock.instant().plusMillis(Backoff.delayMillis(
                transmission.attempts(), properties.initialDelay(), properties.multiplier(),
                properties.maxDelay(), properties.jitter()));
        if (transmissionRepository.markRetry(
                transmission.transmissionId(), transmission.version(),
                describe(exception), next) == 0) {
            log.warn("stale supplier worker could not schedule retry transmissionId={}",
                    transmission.transmissionId());
            return;
        }
        metrics.record("retried");
        log.warn("supplier transmission transient failure transmissionId={} attempts={} next={} error={}",
                transmission.transmissionId(), transmission.attempts(), next, describe(exception));
    }

    private SupplierTransmission require(UUID transmissionId) {
        return transmissionRepository.find(transmissionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Supplier transmission " + transmissionId + " was not found"));
    }

    private String payload(SupplierPurchaseOrder order, UUID transmissionId) {
        try {
            return jsonMapper.writeValueAsString(SupplierOrderPayload.from(order, transmissionId));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Unable to serialize purchase order " + order.poId(), exception);
        }
    }

    private static String hash(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /** Avoid logging supplier response bodies or forged multi-line messages. */
    private static String describe(RuntimeException exception) {
        return exception.getClass().getSimpleName() + ": " + SafeLog.id(exception.getMessage());
    }

    private static SupplierTransmissionView view(SupplierTransmission transmission) {
        return new SupplierTransmissionView(
                transmission.transmissionId(),
                transmission.poId(),
                transmission.supplierId(),
                transmission.status().name(),
                transmission.attempts(),
                transmission.nextAttemptAt(),
                transmission.supplierReference(),
                transmission.lastError(),
                transmission.createdAt(),
                transmission.updatedAt(),
                transmission.sentAt(),
                transmission.ackedAt(),
                transmission.version());
    }
}
