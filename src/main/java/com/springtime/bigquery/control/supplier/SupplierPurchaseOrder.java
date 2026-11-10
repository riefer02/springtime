package com.springtime.bigquery.control.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Minimal purchase-order read model owned by the control plane.
 *
 * <p>The outbox must not depend on the retail domain model or its repository. The retail side
 * adapts its own model to this record when it needs to enqueue a transmission.</p>
 */
public record SupplierPurchaseOrder(
        String poId,
        String poNumber,
        String runId,
        String storeId,
        String supplierId,
        String supplierName,
        String currency,
        BigDecimal totalValue,
        LocalDate expectedDeliveryDate,
        boolean submitted,
        List<Line> lines
) {

    public SupplierPurchaseOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One supplier-facing order line. */
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
