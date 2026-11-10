package com.springtime.bigquery.retail.purchase.store;

import com.springtime.bigquery.control.supplier.SupplierPurchaseOrder;
import com.springtime.bigquery.retail.purchase.application.PurchaseOrderRepository;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLine;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcSupplierPurchaseOrderReaderTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final PurchaseOrderRepository purchaseOrders = mock(PurchaseOrderRepository.class);
    private final JdbcSupplierPurchaseOrderReader reader =
            new JdbcSupplierPurchaseOrderReader(purchaseOrders);

    @Test
    void mapsHeaderStatusAndAllLines() {
        when(purchaseOrders.find("po-1"))
                .thenReturn(Optional.of(order(PurchaseOrderStatus.SUBMITTED)));
        when(purchaseOrders.lines("po-1", 1_000, 0)).thenReturn(List.of(line(1, "SKU-1", 10)));

        SupplierPurchaseOrder supplierOrder = reader.findForTransmission("po-1").orElseThrow();

        assertThat(supplierOrder.poId()).isEqualTo("po-1");
        assertThat(supplierOrder.supplierId()).isEqualTo("SUP-001");
        assertThat(supplierOrder.submitted()).isTrue();
        assertThat(supplierOrder.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineNumber()).isEqualTo(1);
            assertThat(line.sku()).isEqualTo("SKU-1");
            assertThat(line.orderedUnits()).isEqualTo(10);
        });
    }

    @Test
    void reportsDraftsAsNotSubmitted() {
        when(purchaseOrders.find("po-1"))
                .thenReturn(Optional.of(order(PurchaseOrderStatus.DRAFT)));
        when(purchaseOrders.lines("po-1", 1_000, 0)).thenReturn(List.of());

        assertThat(reader.findForTransmission("po-1").orElseThrow().submitted()).isFalse();
    }

    @Test
    void returnsEmptyWhenTheOrderDoesNotExist() {
        when(purchaseOrders.find("missing")).thenReturn(Optional.empty());

        assertThat(reader.findForTransmission("missing")).isEmpty();
    }

    private static PurchaseOrder order(PurchaseOrderStatus status) {
        return new PurchaseOrder(
                "po-1", "PO-1", "run-1:ST-001:SUP-001", "run-1", "ST-001",
                "SUP-001", "Supplier 1", status, "USD", new BigDecimal("10.00"),
                1, "planner", null, LocalDate.of(2026, 11, 20), null, 0, NOW, null, null);
    }

    private static PurchaseOrderLine line(int number, String sku, long units) {
        return new PurchaseOrderLine("po-1", number, sku, sku, "Product " + sku,
                "SUP-001", units, new BigDecimal("10.00"), 1, NOW);
    }
}
