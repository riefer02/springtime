package com.springtime.bigquery.retail.purchase.application;

import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderEventView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderLineView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderEvent;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLine;

/** Domain-to-API mapping for purchase orders. */
final class PurchaseOrderViewMapper {

    private PurchaseOrderViewMapper() {
    }

    static PurchaseOrderView orderView(PurchaseOrder order) {
        return new PurchaseOrderView(
                order.poId(),
                order.poNumber(),
                order.idempotencyKey(),
                order.runId(),
                order.storeId(),
                order.supplierId(),
                order.supplierName(),
                order.status().name(),
                order.currency(),
                order.totalValue() == null ? null : order.totalValue().toPlainString(),
                order.lineCount(),
                order.requestedBy(),
                order.note(),
                order.expectedDeliveryDate(),
                order.supplierReference(),
                order.version(),
                order.createdAt(),
                order.submittedAt(),
                order.cancelledAt());
    }

    static PurchaseOrderLineView lineView(PurchaseOrderLine line) {
        return new PurchaseOrderLineView(
                line.poId(),
                line.lineNumber(),
                line.productId(),
                line.sku(),
                line.productName(),
                line.supplierId(),
                line.orderedUnits(),
                line.lineValue() == null ? null : line.lineValue().toPlainString(),
                line.createdAt());
    }

    static PurchaseOrderEventView eventView(PurchaseOrderEvent event) {
        return new PurchaseOrderEventView(
                event.eventId(),
                event.poId(),
                event.fromStatus() == null ? null : event.fromStatus().name(),
                event.toStatus().name(),
                event.actor(),
                event.note(),
                event.occurredAt());
    }
}
