package com.springtime.bigquery.retail.purchase.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.control.TransactionRunner;
import com.springtime.bigquery.control.supplier.SupplierOutbox;
import com.springtime.bigquery.error.BusinessRuleException;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.error.UpstreamUnavailableException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderEventView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderLineView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderRequest;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderEvent;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLineDraft;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import com.springtime.bigquery.util.Notes;
import com.springtime.bigquery.util.SafeLog;
import com.springtime.bigquery.util.Uuids;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates purchase orders from approved proposals and manages the draft lifecycle.
 *
 * <p>One PO is scoped to one run/store/supplier. The derived idempotency key means a retry of the
 * same decision set returns the existing PO instead of creating a duplicate. Submission records the
 * internal state and an optional supplier reference; the EDI/API transmission adapter is a separate
 * integration.</p>
 */
@Service
public class PurchaseOrderService {

    private static final int MAX_PROPOSALS = 1_000;
    private static final int MAX_LINES = 1_000;
    private static final int MAX_LIST = 200;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseRunReader runReader;
    private final ApprovedProposalQuery approvedProposalQuery;
    private final RetailProperties properties;
    private final PurchaseOrderMetrics metrics;
    private final SupplierOutbox supplierOutbox;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrderRepository,
                                PurchaseRunReader runReader,
                                ApprovedProposalQuery approvedProposalQuery,
                                RetailProperties properties,
                                PurchaseOrderMetrics metrics,
                                SupplierOutbox supplierOutbox,
                                TransactionRunner transactionRunner,
                                Clock clock) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.runReader = runReader;
        this.approvedProposalQuery = approvedProposalQuery;
        this.properties = properties;
        this.metrics = metrics;
        this.supplierOutbox = supplierOutbox;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
    }

    public PurchaseOrderView create(PurchaseOrderRequest request, String actor) {
        // Idempotency wins over mutable validations: a retry after the delivery date passes or the
        // run changes must still return the order created by the first request.
        String idempotencyKey = idempotencyKey(request);
        var existing = purchaseOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            PurchaseOrder order = existing.get();
            if (!order.runId().equals(request.runId())
                    || !order.storeId().equals(request.storeId())
                    || !order.supplierId().equals(request.supplierId())
                    || !Objects.equals(
                            order.expectedDeliveryDate(), request.expectedDeliveryDate())) {
                throw new ConflictException(
                        "idempotencyKey is already used by a different purchase order request");
            }
            if (order.status() == PurchaseOrderStatus.CANCELLED) {
                throw new ConflictException(
                        "A cancelled purchase order already uses this idempotency key; use a new one");
            }
            return PurchaseOrderViewMapper.orderView(order);
        }

        PurchaseRun run = runReader.find(request.runId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Replenishment run " + request.runId() + " was not found"));
        if (!run.completed()) {
            throw new ConflictException("Replenishment run " + run.runId() + " is not completed");
        }
        if (!run.storeId().equals(request.storeId())) {
            throw new BusinessRuleException("Run store does not match the purchase order store");
        }
        if (request.expectedDeliveryDate() != null
                && request.expectedDeliveryDate().isBefore(properties.today(clock))) {
            throw new InvalidQueryException("expectedDeliveryDate must not be in the past");
        }

        List<ApprovedProposal> approved = approvedProposalQuery.approvedForPo(
                run.runId(), request.storeId(), request.supplierId(), MAX_PROPOSALS + 1);
        if (approved.isEmpty()) {
            throw new ConflictException("No approved proposals for the requested run/store/supplier");
        }
        if (approved.size() > MAX_PROPOSALS) {
            throw new ConflictException(
                    "More than " + MAX_PROPOSALS + " approved proposals for this run/store/supplier; "
                            + "split the order before creating a purchase order");
        }

        BigDecimal total = approved.stream()
                .map(ApprovedProposal::orderValue)
                .map(value -> value == null ? BigDecimal.ZERO : value)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant now = clock.instant();
        String poId = UUID.randomUUID().toString();
        PurchaseOrder draft = new PurchaseOrder(
                poId,
                "PO-" + poId.substring(0, 8).toUpperCase(),
                idempotencyKey,
                run.runId(),
                request.storeId(),
                request.supplierId(),
                approved.get(0).supplierName(),
                PurchaseOrderStatus.DRAFT,
                properties.currency(),
                total,
                approved.size(),
                SafeLog.id(actor),
                safeNote(request.note()),
                request.expectedDeliveryDate(),
                null,
                0,
                now,
                null,
                null);

        PurchaseOrderView created = transactionRunner.run(() -> {
            purchaseOrderRepository.create(draft);
            if (purchaseOrderRepository.find(poId).isEmpty()) {
                // A concurrent request won the idempotency-key insert; return its order.
                return purchaseOrderRepository.findByIdempotencyKey(idempotencyKey)
                        .or(() -> purchaseOrderRepository.findByScope(
                                request.runId(), request.storeId(), request.supplierId()))
                        .map(PurchaseOrderViewMapper::orderView)
                        .orElseThrow(() -> new ConflictException(
                                "Purchase order creation raced; reload and retry"));
            }
            purchaseOrderRepository.insertLines(draft, approved.stream()
                    .map(proposal -> new PurchaseOrderLineDraft(
                            proposal.productId(), proposal.sku(), proposal.productName(),
                            proposal.supplierId(), proposal.recommendedUnits(),
                            proposal.orderValue(), proposal.version()))
                    .toList(), now);
            String note = "created from run " + run.runId();
            purchaseOrderRepository.recordEvent(event(poId, null, PurchaseOrderStatus.DRAFT,
                    actor, note, now));
            return PurchaseOrderViewMapper.orderView(draft);
        });
        metrics.record("created");
        return created;
    }

    public PurchaseOrderView submit(String poId, int expectedVersion,
                                    String supplierReference, String note, String actor) {
        PurchaseOrder order = require(poId);
        if (order.status() != PurchaseOrderStatus.DRAFT) {
            throw new ConflictException("Only a draft purchase order can be submitted");
        }
        if (order.version() != expectedVersion) {
            throw new ConflictException("Purchase order version conflict; current version is "
                    + order.version());
        }
        Instant submittedAt = clock.instant();
        return transactionRunner.run(() -> {
            int updated = purchaseOrderRepository.submit(
                    poId, expectedVersion, supplierReference, submittedAt);
            if (updated != 1) {
                throw new ConflictException(
                        "Purchase order changed while submitting; reload and retry");
            }
            recordEventOrFail(poId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.SUBMITTED,
                    actor, safeNote(note), submittedAt);
            supplierOutbox.enqueue(poId, actor);
            metrics.record("submitted");
            return PurchaseOrderViewMapper.orderView(require(poId));
        });
    }

    public PurchaseOrderView cancel(String poId, int expectedVersion, String note, String actor) {
        PurchaseOrder order = require(poId);
        if (order.status() != PurchaseOrderStatus.DRAFT) {
            throw new ConflictException("Only a draft purchase order can be cancelled");
        }
        if (order.version() != expectedVersion) {
            throw new ConflictException("Purchase order version conflict; current version is "
                    + order.version());
        }
        Instant cancelledAt = clock.instant();
        return transactionRunner.run(() -> {
            int updated = purchaseOrderRepository.cancel(poId, expectedVersion, cancelledAt);
            if (updated != 1) {
                throw new ConflictException(
                        "Purchase order changed while cancelling; reload and retry");
            }
            recordEventOrFail(poId, PurchaseOrderStatus.DRAFT, PurchaseOrderStatus.CANCELLED,
                    actor, safeNote(note), cancelledAt);
            metrics.record("cancelled");
            return PurchaseOrderViewMapper.orderView(require(poId));
        });
    }

    public PurchaseOrderView get(String poId) {
        return PurchaseOrderViewMapper.orderView(require(poId));
    }

    public PageResponse<PurchaseOrderView> list(String storeId, String supplierId,
                                                PurchaseOrderStatus status,
                                                String pageToken, int limit) {
        int pageSize = Math.min(Math.max(1, limit), MAX_LIST);
        int offset = PageToken.decodeOffset(pageToken);
        List<PurchaseOrderView> fetched =
                purchaseOrderRepository.list(storeId, supplierId, status, pageSize + 1, offset)
                        .stream()
                        .map(PurchaseOrderViewMapper::orderView)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    public PageResponse<PurchaseOrderLineView> lines(String poId, String pageToken, int limit) {
        require(poId);
        int pageSize = Math.min(Math.max(1, limit), MAX_LINES);
        int offset = PageToken.decodeOffset(pageToken);
        List<PurchaseOrderLineView> fetched =
                purchaseOrderRepository.lines(poId, pageSize + 1, offset).stream()
                        .map(PurchaseOrderViewMapper::lineView)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    public PageResponse<PurchaseOrderEventView> history(String poId, String pageToken, int limit) {
        require(poId);
        int pageSize = Math.min(Math.max(1, limit), MAX_LIST);
        int offset = PageToken.decodeOffset(pageToken);
        List<PurchaseOrderEventView> fetched =
                purchaseOrderRepository.events(poId, pageSize + 1, offset).stream()
                        .map(PurchaseOrderViewMapper::eventView)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    private PurchaseOrder require(String poId) {
        if (!Uuids.isValid(poId)) {
            throw new ResourceNotFoundException(
                    "Purchase order " + SafeLog.id(poId) + " was not found");
        }
        return purchaseOrderRepository.find(poId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Purchase order " + SafeLog.id(poId) + " was not found"));
    }

    private void recordEventOrFail(String poId, PurchaseOrderStatus from, PurchaseOrderStatus to,
                                   String actor, String note, Instant at) {
        try {
            purchaseOrderRepository.recordEvent(event(poId, from, to, actor, note, at));
        } catch (RuntimeException exception) {
            // The surrounding transaction rolls the state change back, so no partial PO is kept.
            throw new UpstreamUnavailableException(
                    "The purchase order lifecycle event could not be recorded", exception);
        }
    }

    private static PurchaseOrderEvent event(String poId, PurchaseOrderStatus from,
                                            PurchaseOrderStatus to, String actor,
                                            String note, Instant at) {
        return new PurchaseOrderEvent(
                UUID.randomUUID().toString(), poId, from, to,
                SafeLog.id(actor), safeNote(note), at);
    }

    private static String idempotencyKey(PurchaseOrderRequest request) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request.idempotencyKey();
        }
        return request.runId() + ":" + request.storeId() + ":" + request.supplierId();
    }

    private static String safeNote(String note) {
        if (note == null) {
            return null;
        }
        return Notes.safeNote(note, Notes.MAX_NOTE);
    }
}
