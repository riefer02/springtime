package com.springtime.bigquery.control.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Stable supplier-facing payload stored in the outbox.
 *
 * <p>This is deliberately independent of the retail domain records; changing the internal PO model
 * must not silently change the bytes a supplier receives.</p>
 */
public record SupplierOrderPayload(
        int schemaVersion,
        String transmissionId,
        String poId,
        String poNumber,
        String runId,
        String storeId,
        String supplierId,
        String supplierName,
        String currency,
        BigDecimal totalValue,
        LocalDate expectedDeliveryDate,
        List<Line> lines
) {

    public SupplierOrderPayload {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public static SupplierOrderPayload from(SupplierPurchaseOrder order, UUID transmissionId) {
        return new SupplierOrderPayload(
                1,
                transmissionId.toString(),
                order.poId(),
                order.poNumber(),
                order.runId(),
                order.storeId(),
                order.supplierId(),
                order.supplierName(),
                order.currency(),
                order.totalValue(),
                order.expectedDeliveryDate(),
                order.lines().stream().map(SupplierOrderPayload::from).toList());
    }

    private static Line from(SupplierPurchaseOrder.Line line) {
        return new Line(line.lineNumber(), line.productId(), line.sku(), line.productName(),
                line.orderedUnits(), line.lineValue());
    }

    /** One line in the supplier payload. */
    public record Line(
            int lineNumber,
            String productId,
            String sku,
            String productName,
            long orderedUnits,
            BigDecimal lineValue
    ) {
    }
}
