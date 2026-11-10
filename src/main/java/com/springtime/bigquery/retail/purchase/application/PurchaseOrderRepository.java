package com.springtime.bigquery.retail.purchase.application;

import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderEvent;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLine;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLineDraft;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Storage boundary for purchase orders, lines, and lifecycle events. */
public interface PurchaseOrderRepository {

    Optional<PurchaseOrder> find(String poId);

    Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey);

    Optional<PurchaseOrder> findByScope(String runId, String storeId, String supplierId);

    List<PurchaseOrder> list(String storeId, String supplierId, PurchaseOrderStatus status,
                             int limit, int offset);

    void create(PurchaseOrder order);

    void insertLines(PurchaseOrder order, List<PurchaseOrderLineDraft> lines, Instant createdAt);

    List<PurchaseOrderLine> lines(String poId, int limit, int offset);

    int submit(String poId, int expectedVersion, String supplierReference, Instant submittedAt);

    int cancel(String poId, int expectedVersion, Instant cancelledAt);

    void recordEvent(PurchaseOrderEvent event);

    List<PurchaseOrderEvent> events(String poId, int limit, int offset);
}
