package com.springtime.bigquery.control.supplier;

import com.springtime.bigquery.control.TestTransactionRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierTransmissionServiceTest {

    private static final String PO = "11111111-1111-1111-1111-111111111111";
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SupplierPurchaseOrderReader purchaseOrders = mock(SupplierPurchaseOrderReader.class);
    private final InMemorySupplierTransmissionRepository repository =
            new InMemorySupplierTransmissionRepository(CLOCK);
    private final StubSupplierGateway gateway = new StubSupplierGateway();
    private final SupplierTransmissionService service = new SupplierTransmissionService(
            purchaseOrders, repository, gateway, properties(), JsonMapper.builder().build(),
            new SupplierTransmissionMetrics(new SimpleMeterRegistry()),
            new TestTransactionRunner(), CLOCK);

    @Test
    void enqueuesSubmittedOrdersIdempotently() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));

        var first = service.enqueueForPo(PO, "planner");
        var second = service.enqueueForPo(PO, "planner");

        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(second.transmissionId()).isEqualTo(first.transmissionId());
    }

    @Test
    void payloadIncludesEveryPurchaseOrderLine() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));

        service.enqueueForPo(PO, "planner");

        String payload = repository.findByPoId(PO).orElseThrow().payload();
        assertThat(payload)
                .contains("\"schemaVersion\":1")
                .contains("\"transmissionId\":\"")
                .contains("\"poNumber\":\"PO-1\"")
                .contains("\"lines\":[")
                .contains("\"sku\":\"SKU-1\"")
                .contains("\"sku\":\"SKU-2\"")
                .contains("\"orderedUnits\":20");
    }

    @Test
    void doesNotRetryWhenTheSupplierAcceptedButMarkSentFailed() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));
        InMemorySupplierTransmissionRepository spyRepository =
                spy(new InMemorySupplierTransmissionRepository(CLOCK));
        doThrow(new IllegalStateException("database unavailable"))
                .when(spyRepository).markSent(any(), anyLong(), any(), any());
        SupplierTransmissionService failingService = new SupplierTransmissionService(
                purchaseOrders, spyRepository, gateway, properties(),
                JsonMapper.builder().build(),
                new SupplierTransmissionMetrics(new SimpleMeterRegistry()),
                new TestTransactionRunner(), CLOCK);

        var transmission = failingService.enqueueForPo(PO, "planner");
        gateway.result = new SupplierSubmission("SUP-1", NOW);

        failingService.processDue();

        var view = failingService.get(transmission.transmissionId());
        assertThat(view.status()).isEqualTo("SENDING");
        assertThat(view.attempts()).isEqualTo(1);
        verify(spyRepository, never()).markRetry(any(), anyLong(), any(), any());
    }

    @Test
    void rejectsAMalformedPurchaseOrderIdWithoutReadingTheOrder() {
        assertThatThrownBy(() -> service.enqueueForPo("not-a-uuid", "planner"))
                .isInstanceOf(com.springtime.bigquery.error.ResourceNotFoundException.class);
        verify(purchaseOrders, never()).findForTransmission("not-a-uuid");
    }

    @Test
    void rejectsTransmissionForADraft() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(false)));

        assertThatThrownBy(() -> service.enqueueForPo(PO, "planner"))
                .isInstanceOf(com.springtime.bigquery.error.BusinessRuleException.class)
                .hasMessageContaining("submitted");
    }

    @Test
    void marksSentOnSuccessAndAcknowledges() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));
        var transmission = service.enqueueForPo(PO, "planner");
        gateway.result = new SupplierSubmission("SUP-1", NOW);

        service.processDue();
        assertThat(service.get(transmission.transmissionId()).status()).isEqualTo("SENT");

        var acked = service.acknowledge(transmission.transmissionId(), "SUP-ACK", "planner");
        assertThat(acked.status()).isEqualTo("ACKED");
        assertThat(acked.supplierReference()).isEqualTo("SUP-ACK");
    }

    @Test
    void retriesTransientFailure() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));
        var transmission = service.enqueueForPo(PO, "planner");
        gateway.failure = new SupplierTransientException("network", null);

        service.processDue();

        var view = service.get(transmission.transmissionId());
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(view.lastError()).contains("network");
    }

    @Test
    void failsPermanentAndAllowsExplicitRetry() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));
        var transmission = service.enqueueForPo(PO, "planner");
        gateway.failure = new SupplierPermanentException("bad schema");

        service.processDue();
        assertThat(service.get(transmission.transmissionId()).status()).isEqualTo("FAILED");

        var retried = service.retry(transmission.transmissionId(), "planner");
        assertThat(retried.status()).isEqualTo("PENDING");
    }

    @Test
    void ackRequiresASentTransmission() {
        when(purchaseOrders.findForTransmission(PO)).thenReturn(Optional.of(order(true)));
        var transmission = service.enqueueForPo(PO, "planner");

        assertThatThrownBy(() -> service.acknowledge(transmission.transmissionId(), null, "planner"))
                .isInstanceOf(com.springtime.bigquery.error.BusinessRuleException.class)
                .hasMessageContaining("SENT");
    }

    @Test
    void pagesTransmissionsAndRejectsMalformedTokens() {
        repository.enqueue(transmissionAt("po-page-1", NOW.minusSeconds(3)));
        repository.enqueue(transmissionAt("po-page-2", NOW.minusSeconds(2)));
        repository.enqueue(transmissionAt("po-page-3", NOW.minusSeconds(1)));

        var first = service.list(null, null, 2);

        assertThat(first.items()).extracting("poId")
                .containsExactly("po-page-3", "po-page-2");
        assertThat(first.pageSize()).isEqualTo(2);
        assertThat(first.nextPageToken()).isNotNull();

        var second = service.list(null, first.nextPageToken(), 2);

        assertThat(second.items()).extracting("poId").containsExactly("po-page-1");
        assertThat(second.nextPageToken()).isNull();

        assertThatThrownBy(() -> service.list(null, "not-a-token", 2))
                .isInstanceOf(com.springtime.bigquery.error.InvalidRequestException.class);
    }

    private static SupplierTransmission transmissionAt(String poId, Instant createdAt) {
        return new SupplierTransmission(
                java.util.UUID.randomUUID(), poId, "SUP-001",
                SupplierTransmissionStatus.PENDING, 0, NOW, "{}", "hash",
                null, null, createdAt, createdAt, null, null, 0);
    }

    private static SupplierPurchaseOrder order(boolean submitted) {
        return new SupplierPurchaseOrder(
                PO, "PO-1", "run-1", "ST-001", "SUP-001", "Supplier 1", "USD",
                new BigDecimal("30.00"), LocalDate.of(2026, 11, 20), submitted,
                java.util.List.of(
                        new SupplierPurchaseOrder.Line(
                                1, "SKU-1", "SKU-1", "Product SKU-1", 10, new BigDecimal("10.00")),
                        new SupplierPurchaseOrder.Line(
                                2, "SKU-2", "SKU-2", "Product SKU-2", 20, new BigDecimal("20.00"))));
    }

    private static SupplierOutboxProperties properties() {
        return new SupplierOutboxProperties(
                true, true, 5_000, 20, 2,
                Duration.ofMillis(10), 2.0, Duration.ofSeconds(1), 0.0);
    }

    private static final class StubSupplierGateway implements SupplierGateway {
        private SupplierSubmission result = new SupplierSubmission("SUP-1", NOW);
        private RuntimeException failure;

        @Override
        public SupplierSubmission submit(SupplierTransmission transmission) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
