package com.springtime.bigquery.retail.purchase.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.control.supplier.SupplierOutbox;
import com.springtime.bigquery.control.TestTransactionRunner;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderRequest;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLineDraft;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseOrderServiceTest {

    private static final String PO = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate LATEST = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final PurchaseOrderRepository purchaseOrders = org.mockito.Mockito.mock(PurchaseOrderRepository.class);
    private final PurchaseRunReader runs = org.mockito.Mockito.mock(PurchaseRunReader.class);
    private final ApprovedProposalQuery approved = org.mockito.Mockito.mock(ApprovedProposalQuery.class);
    private final SupplierOutbox transmissions = org.mockito.Mockito.mock(SupplierOutbox.class);
    private final PurchaseOrderService service = new PurchaseOrderService(
            purchaseOrders, runs, approved, properties(),
            new PurchaseOrderMetrics(new SimpleMeterRegistry()), transmissions,
            new TestTransactionRunner(), CLOCK);

    @Test
    void createsADraftFromApprovedProposals() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(approved.approvedForPo("run-1", "ST-001", "SUP-001", 1_001))
                .thenReturn(List.of(proposal("SKU-1", "10.00"), proposal("SKU-2", "20.00")));
        when(purchaseOrders.find(anyString())).thenAnswer(invocation ->
                Optional.of(order(invocation.getArgument(0), PurchaseOrderStatus.DRAFT, 0)));
        PurchaseOrderView view = service.create(request(), "planner");

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.lineCount()).isEqualTo(2);
        assertThat(view.totalValue()).isEqualTo("30.00");
        assertThat(view.supplierId()).isEqualTo("SUP-001");
        verify(purchaseOrders).create(any());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<PurchaseOrderLineDraft>> lines =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(purchaseOrders).insertLines(any(PurchaseOrder.class), lines.capture(), any());
        assertThat(lines.getValue()).hasSize(2);
        assertThat(lines.getValue().get(0).productId()).isEqualTo("SKU-1");
        assertThat(lines.getValue().get(0).sourceProposalVersion()).isEqualTo(1);
        verify(purchaseOrders).recordEvent(any());
    }

    @Test
    void returnsTheExistingOrderForTheSameIdempotencyKey() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(order(PO, PurchaseOrderStatus.DRAFT, 0)));

        PurchaseOrderView view = service.create(request(), "planner");

        assertThat(view.poId()).isEqualTo(PO);
        verify(approved, never()).approvedForPo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void rejectsAReusedKeyWithADifferentRequestShape() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(order("po-1", PurchaseOrderStatus.DRAFT, 0)));

        PurchaseOrderRequest otherSupplier = new PurchaseOrderRequest(
                null, "run-1", "ST-001", "SUP-999", null, null);

        assertThatThrownBy(() -> service.create(otherSupplier, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different purchase order request");
    }

    @Test
    void returnsAnExistingOrderForTheSameScopeWithADifferentClientKey() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey("client-key")).thenReturn(Optional.empty());
        when(approved.approvedForPo("run-1", "ST-001", "SUP-001", 1_001))
                .thenReturn(List.of(proposal("SKU-1", "10.00")));
        when(purchaseOrders.find(anyString())).thenReturn(Optional.empty());
        when(purchaseOrders.findByScope("run-1", "ST-001", "SUP-001"))
                .thenReturn(Optional.of(order("po-existing", PurchaseOrderStatus.DRAFT, 0)));

        PurchaseOrderRequest request = new PurchaseOrderRequest(
                "client-key", "run-1", "ST-001", "SUP-001", null, null);
        PurchaseOrderView view = service.create(request, "planner");

        assertThat(view.poId()).isEqualTo("po-existing");
        verify(purchaseOrders, never()).insertLines(any(), any(), any());
    }

    @Test
    void rejectsAnOrderThatNeedsMoreThanTheLineCap() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(approved.approvedForPo("run-1", "ST-001", "SUP-001", 1_001))
                .thenReturn(java.util.Collections.nCopies(1_001, proposal("SKU-1", "10.00")));

        assertThatThrownBy(() -> service.create(request(), "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("split the order");
    }

    @Test
    void returnsTheExistingOrderBeforeRevalidatingMutableRunState() {
        when(purchaseOrders.findByIdempotencyKey(anyString()))
                .thenReturn(Optional.of(order("po-1", PurchaseOrderStatus.DRAFT, 0)));

        PurchaseOrderView view = service.create(request(), "planner");

        assertThat(view.poId()).isEqualTo("po-1");
        verify(runs, never()).find(anyString());
        verify(approved, never()).approvedForPo(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void rejectsWhenThereAreNoApprovedProposals() {
        when(runs.find("run-1")).thenReturn(Optional.of(run()));
        when(purchaseOrders.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(approved.approvedForPo(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(request(), "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("approved");
    }

    @Test
    void rejectsAnIncompleteRun() {
        when(runs.find("run-1")).thenReturn(Optional.of(
                new PurchaseRun("run-1", "ST-001", false)));

        assertThatThrownBy(() -> service.create(request(), "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not completed");
    }

    @Test
    void submitsADraftWithTheExpectedVersion() {
        when(purchaseOrders.find(PO))
                .thenReturn(Optional.of(order(PO, PurchaseOrderStatus.DRAFT, 0)),
                        Optional.of(order(PO, PurchaseOrderStatus.SUBMITTED, 1)));
        when(purchaseOrders.submit(PO, 0, "SUP-REF-1", NOW)).thenReturn(1);

        PurchaseOrderView view = service.submit(PO, 0, "SUP-REF-1", "sent", "planner");

        assertThat(view.status()).isEqualTo("SUBMITTED");
        verify(purchaseOrders).recordEvent(any());
        verify(transmissions).enqueue(PO, "planner");
    }

    @Test
    void rejectsAMalformedIdWithoutHittingTheRepository() {
        assertThatThrownBy(() -> service.get("not-a-uuid"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(purchaseOrders, never()).find("not-a-uuid");
    }

    @Test
    void rejectsAStaleSubmitVersion() {
        when(purchaseOrders.find(PO))
                .thenReturn(Optional.of(order(PO, PurchaseOrderStatus.DRAFT, 2)));

        assertThatThrownBy(() -> service.submit(PO, 1, null, null, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("version");
    }

    @Test
    void cancelsADraft() {
        when(purchaseOrders.find(PO))
                .thenReturn(Optional.of(order(PO, PurchaseOrderStatus.DRAFT, 0)),
                        Optional.of(order(PO, PurchaseOrderStatus.CANCELLED, 1)));
        when(purchaseOrders.cancel(PO, 0, NOW)).thenReturn(1);

        PurchaseOrderView view = service.cancel(PO, 0, "duplicate", "planner");

        assertThat(view.status()).isEqualTo("CANCELLED");
        verify(transmissions, never()).enqueue(anyString(), anyString());
    }

    @Test
    void pagesPurchaseOrdersAndRejectsMalformedTokens() {
        when(purchaseOrders.list(null, null, null, 2, 0))
                .thenReturn(List.of(order("po-2", PurchaseOrderStatus.DRAFT, 0),
                        order("po-1", PurchaseOrderStatus.DRAFT, 0)));
        when(purchaseOrders.list(null, null, null, 2, 1))
                .thenReturn(List.of(order("po-1", PurchaseOrderStatus.DRAFT, 0)));

        var first = service.list(null, null, null, null, 1);

        assertThat(first.items()).extracting(PurchaseOrderView::poId).containsExactly("po-2");
        assertThat(first.pageSize()).isEqualTo(1);
        assertThat(first.nextPageToken()).isNotNull();

        var second = service.list(null, null, null, first.nextPageToken(), 1);

        assertThat(second.items()).extracting(PurchaseOrderView::poId).containsExactly("po-1");
        assertThat(second.nextPageToken()).isNull();

        assertThatThrownBy(() -> service.list(null, null, null, "not-a-token", 1))
                .isInstanceOf(com.springtime.bigquery.error.InvalidRequestException.class);
    }

    private static PurchaseOrderRequest request() {
        return new PurchaseOrderRequest(null, "run-1", "ST-001", "SUP-001", null, null);
    }

    private static PurchaseRun run() {
        return new PurchaseRun("run-1", "ST-001", true);
    }

    private static ApprovedProposal proposal(String productId, String value) {
        return new ApprovedProposal(
                productId, productId, "Product " + productId, "SUP-001", "Supplier 1",
                10, new BigDecimal(value), 1);
    }

    private static PurchaseOrder order(String poId, PurchaseOrderStatus status, int version) {
        return new PurchaseOrder(
                poId, "PO-" + poId, "run-1:ST-001:SUP-001", "run-1", "ST-001",
                "SUP-001", "Supplier 1", status, "USD", new BigDecimal("30.00"),
                2, "planner", null, null, null, version, NOW,
                status == PurchaseOrderStatus.SUBMITTED ? NOW : null,
                status == PurchaseOrderStatus.CANCELLED ? NOW : null);
    }

    private static RetailProperties properties() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 268_435_456L,
                "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    }
}
