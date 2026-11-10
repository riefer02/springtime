package com.springtime.bigquery.retail.purchase.store;

import com.springtime.bigquery.control.supplier.SupplierPurchaseOrder;
import com.springtime.bigquery.control.supplier.SupplierPurchaseOrderReader;
import com.springtime.bigquery.retail.purchase.application.PurchaseOrderRepository;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLine;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapts the retail purchase-order model to the control-plane outbox read port.
 *
 * <p>This is the only place that knows both sides. It keeps {@code control.supplier} free of any
 * retail dependency, so the purchase → control dependency is one-way.</p>
 */
@Component
public class JdbcSupplierPurchaseOrderReader implements SupplierPurchaseOrderReader {

    private static final int MAX_LINES = 1_000;

    private final PurchaseOrderRepository purchaseOrderRepository;

    public JdbcSupplierPurchaseOrderReader(PurchaseOrderRepository purchaseOrderRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    @Override
    public Optional<SupplierPurchaseOrder> findForTransmission(String poId) {
        return purchaseOrderRepository.find(poId).map(this::toSupplierOrder);
    }

    private SupplierPurchaseOrder toSupplierOrder(PurchaseOrder order) {
        return new SupplierPurchaseOrder(
                order.poId(),
                order.poNumber(),
                order.runId(),
                order.storeId(),
                order.supplierId(),
                order.supplierName(),
                order.currency(),
                order.totalValue(),
                order.expectedDeliveryDate(),
                order.status() == PurchaseOrderStatus.SUBMITTED,
                purchaseOrderRepository.lines(order.poId(), MAX_LINES, 0).stream()
                        .map(JdbcSupplierPurchaseOrderReader::toLine)
                        .toList());
    }

    private static SupplierPurchaseOrder.Line toLine(PurchaseOrderLine line) {
        return new SupplierPurchaseOrder.Line(
                line.lineNumber(),
                line.productId(),
                line.sku(),
                line.productName(),
                line.orderedUnits(),
                line.lineValue());
    }
}
