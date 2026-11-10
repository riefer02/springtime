package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.retail.inventory.model.DateBounds;
import com.springtime.bigquery.retail.inventory.model.ProjectionResult;
import com.springtime.bigquery.retail.inventory.query.InventoryLedgerQueryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryProjectionServiceTest {

    private static final LocalDate EARLIEST = LocalDate.of(2026, 8, 12);
    private static final LocalDate LATEST = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    private final InventoryLedgerQueryPort ledger = mock(InventoryLedgerQueryPort.class);
    private final InventoryProjectionStore store = mock(InventoryProjectionStore.class);
    private final RunLease runLease = mock(RunLease.class);
    private final RetailProperties retailProperties = new RetailProperties(
            "retail_demo", 50, 200, 28, 14, 268_435_456L,
            "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    private final InventoryProjectionService service = new InventoryProjectionService(
            ledger, store, retailProperties,
            new InventoryMetrics(new SimpleMeterRegistry()),
            runLease, CLOCK);

    @org.junit.jupiter.api.BeforeEach
    void leaseIsAvailable() {
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void refusesAConcurrentProjectionRefresh() {
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.rebuild())
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already running");
    }

    @Test
    void rebuildsFromTheLedgerBounds() {
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(store.rebuild()).thenReturn(240L);

        ProjectionResult result = service.rebuild();

        assertThat(result.status()).isEqualTo("REBUILT");
        assertThat(result.fromDate()).isEqualTo(EARLIEST);
        assertThat(result.toDate()).isEqualTo(LATEST);
        assertThat(result.affectedRows()).isEqualTo(240);
        assertThat(result.projectedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void incrementalUsesLookbackAndMergesTheRange() {
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(ledger.projectionBounds()).thenReturn(new DateBounds(EARLIEST, LATEST.minusDays(1)));
        when(store.mergeRange(LATEST.minusDays(8), LATEST)).thenReturn(100L);

        ProjectionResult result = service.incremental(7);

        assertThat(result.status()).isEqualTo("INCREMENTAL");
        assertThat(result.fromDate()).isEqualTo(LATEST.minusDays(8));
        assertThat(result.toDate()).isEqualTo(LATEST);
        assertThat(result.affectedRows()).isEqualTo(100);
    }

    @Test
    void incrementalFallsBackToRebuildWhenTheProjectionStartsLate() {
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(ledger.projectionBounds()).thenReturn(
                new DateBounds(EARLIEST.plusDays(1), LATEST.minusDays(1)));
        when(store.rebuild()).thenReturn(240L);

        ProjectionResult result = service.incremental(7);

        assertThat(result.status()).isEqualTo("REBUILT");
        assertThat(result.affectedRows()).isEqualTo(240);
    }

    @Test
    void incrementalStartsAtTheOldestUnprojectedMovement() {
        LocalDate late = LATEST.minusDays(30);
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(ledger.projectionBounds()).thenReturn(new DateBounds(EARLIEST, LATEST.minusDays(1)));
        when(ledger.earliestUnprojectedBusinessDate()).thenReturn(late);
        when(store.mergeRange(late, LATEST)).thenReturn(100L);

        ProjectionResult result = service.incremental(7);

        assertThat(result.status()).isEqualTo("INCREMENTAL");
        assertThat(result.fromDate()).isEqualTo(late);
    }

    @Test
    void incrementalFallsBackToRebuildWhenProjectionIsEmpty() {
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(ledger.projectionBounds()).thenReturn(DateBounds.empty());
        when(store.rebuild()).thenReturn(240L);

        ProjectionResult result = service.incremental(7);

        assertThat(result.status()).isEqualTo("REBUILT");
        assertThat(result.affectedRows()).isEqualTo(240);
    }

    @Test
    void returnsNoDataWhenTheLedgerIsEmpty() {
        when(ledger.ledgerBounds()).thenReturn(DateBounds.empty());

        ProjectionResult result = service.rebuild();

        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.affectedRows()).isZero();
    }

    @Test
    void returnsUpToDateWhenTheProjectionIsAhead() {
        when(ledger.ledgerBounds()).thenReturn(new DateBounds(EARLIEST, LATEST));
        when(ledger.projectionBounds()).thenReturn(new DateBounds(EARLIEST, LATEST.plusDays(10)));

        ProjectionResult result = service.incremental(7);

        assertThat(result.status()).isEqualTo("UP_TO_DATE");
        assertThat(result.affectedRows()).isZero();
    }
}
