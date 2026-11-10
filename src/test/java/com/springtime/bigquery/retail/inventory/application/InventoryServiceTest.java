package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.inventory.model.InventoryDrift;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;
import com.springtime.bigquery.retail.inventory.model.ReconciliationReport;
import com.springtime.bigquery.retail.inventory.query.InventoryLedgerQueryPort;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InventoryServiceTest {

    private static final LocalDate LATEST = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    private final InventoryLedgerQueryPort ledger = mock(InventoryLedgerQueryPort.class);
    private final RetailQueryPort retail = mock(RetailQueryPort.class);
    private final InventoryService service = new InventoryService(
            ledger, retail, properties(), new InventoryMetrics(new SimpleMeterRegistry()), CLOCK);

    @BeforeEach
    void latestSnapshot() {
        org.mockito.Mockito.lenient().when(retail.latestSnapshotDate()).thenReturn(LATEST);
        org.mockito.Mockito.lenient().when(retail.latestLedgerDate()).thenReturn(LATEST);
        org.mockito.Mockito.lenient().when(retail.storeExists(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
    }

    @Test
    void unknownStoreIsNotFoundRatherThanBalanced() {
        org.mockito.Mockito.when(retail.storeExists("ST-404")).thenReturn(false);
        org.mockito.Mockito.when(ledger.reconciliation(org.mockito.ArgumentMatchers.eq("ST-404"), org.mockito.ArgumentMatchers.eq(LATEST), org.mockito.ArgumentMatchers.eq(0L), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.reconciliation("ST-404", null, null, 50))
                .isInstanceOf(com.springtime.bigquery.error.ResourceNotFoundException.class)
                .hasMessageContaining("was not found");
    }

    @Test
    void defaultsMovementsToLatestSevenDays() {
        InventoryMovementView movement = new InventoryMovementView(
                "evt-1", "SALE", Instant.parse("2026-11-09T11:00:00Z"), LATEST,
                "ST-001", "SKU-0001", -2L, null, "USD",
                "retail-api", "evt-1", "1");
        when(ledger.movements("ST-001", null, LATEST.minusDays(7), LATEST, 51, 0))
                .thenReturn(List.of(movement));

        PageResponse<InventoryMovementView> page =
                service.movements("ST-001", null, null, null, null, 50);

        assertThat(page.items()).containsExactly(movement);
        assertThat(page.pageSize()).isEqualTo(50);
        assertThat(page.nextPageToken()).isNull();
        verify(ledger).movements("ST-001", null, LATEST.minusDays(7), LATEST, 51, 0);
    }

    @Test
    void pagesMovementsAndReturnsATokenOnlyWhenAnotherPageExists() {
        InventoryMovementView first = new InventoryMovementView(
                "evt-1", "SALE", Instant.parse("2026-11-09T11:00:00Z"), LATEST,
                "ST-001", "SKU-0001", -2L, null, "USD",
                "retail-api", "evt-1", "1");
        InventoryMovementView second = new InventoryMovementView(
                "evt-2", "SALE", Instant.parse("2026-11-09T10:00:00Z"), LATEST,
                "ST-001", "SKU-0002", -1L, null, "USD",
                "retail-api", "evt-2", "1");
        when(ledger.movements("ST-001", null, LATEST.minusDays(7), LATEST, 2, 0))
                .thenReturn(List.of(first, second));
        when(ledger.movements("ST-001", null, LATEST.minusDays(7), LATEST, 2, 1))
                .thenReturn(List.of(second));

        PageResponse<InventoryMovementView> firstPage =
                service.movements("ST-001", null, null, null, null, 1);

        assertThat(firstPage.items()).containsExactly(first);
        assertThat(PageToken.decodeOffset(firstPage.nextPageToken())).isEqualTo(1);

        PageResponse<InventoryMovementView> secondPage =
                service.movements("ST-001", null, null, null, firstPage.nextPageToken(), 1);

        assertThat(secondPage.items()).containsExactly(second);
        assertThat(secondPage.nextPageToken()).isNull();
    }

    @Test
    void rejectsAMalformedMovementPageToken() {
        assertThatThrownBy(() -> service.movements("ST-001", null, null, null, "not-a-token", 50))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void reportsMissingLedgerAndSnapshotData() {
        org.mockito.Mockito.lenient().when(retail.latestSnapshotDate()).thenReturn(null);
        org.mockito.Mockito.lenient().when(retail.latestLedgerDate()).thenReturn(null);

        assertThatThrownBy(() -> service.movements("ST-001", null, null, null, null, 50))
                .isInstanceOf(com.springtime.bigquery.error.DataNotReadyException.class)
                .hasMessageContaining("No ledger or snapshot");
    }

    @Test
    void rejectsAFutureMovementEndDate() {
        assertThatThrownBy(() -> service.movements(
                "ST-001", null, null, LATEST.plusDays(1), null, 50))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("future");
    }

    @Test
    void rejectsInvertedMovementRange() {
        assertThatThrownBy(() -> service.movements(
                "ST-001", null, LATEST, LATEST.minusDays(1), null, 50))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("from");
    }

    @Test
    void reportsBalancedWhenNoDrift() {
        when(ledger.reconciliation(null, LATEST, 0, 51)).thenReturn(List.of());

        ReconciliationReport report = service.reconciliation(null, LATEST, null, 50);

        assertThat(report.status()).isEqualTo("BALANCED");
        assertThat(report.driftPairs()).isZero();
        assertThat(report.maxAbsoluteDrift()).isZero();
        assertThat(report.limit()).isEqualTo(50);
        assertThat(report.truncated()).isFalse();
        assertThat(report.checkedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void reportsDriftAndMaxAbsoluteValue() {
        when(ledger.reconciliation("ST-001", LATEST, 2, 51)).thenReturn(List.of(
                new InventoryDrift("ST-001", "SKU-1", 10, 8, 2),
                new InventoryDrift("ST-001", "SKU-2", 5, 12, -7)));

        ReconciliationReport report = service.reconciliation("ST-001", LATEST, 2L, 50);

        assertThat(report.status()).isEqualTo("DRIFT");
        assertThat(report.driftPairs()).isEqualTo(2);
        assertThat(report.maxAbsoluteDrift()).isEqualTo(7);
        assertThat(report.tolerance()).isEqualTo(2);
    }

    @Test
    void marksTheReportTruncatedAtTheRequestedLimit() {
        when(ledger.reconciliation(null, LATEST, 0, 2)).thenReturn(List.of(
                new InventoryDrift("ST-001", "SKU-1", 10, 8, 2),
                new InventoryDrift("ST-001", "SKU-2", 5, 1, 4)));

        ReconciliationReport report = service.reconciliation(null, LATEST, null, 1);

        assertThat(report.limit()).isEqualTo(1);
        assertThat(report.truncated()).isTrue();
        assertThat(report.driftPairs()).isEqualTo(1);
    }

    @Test
    void rejectsFutureReconciliationDate() {
        assertThatThrownBy(() -> service.reconciliation(
                "ST-001", LATEST.plusDays(1), null, 50))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("future");
    }

    private static RetailProperties properties() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 268_435_456L,
                "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    }
}
