package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.retail.inventory.model.DateBounds;
import com.springtime.bigquery.retail.inventory.model.ProjectionResult;
import com.springtime.bigquery.retail.inventory.query.InventoryLedgerQueryPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Maintains the on-hand projection from the append-only ledger.
 *
 * <p>Incremental refresh recomputes a lookback window so late events are corrected. Full refresh
 * rebuilds every movement date; it is an admin operation, not the request path.</p>
 */
@Service
public class InventoryProjectionService {

    private static final int MIN_LOOKBACK_DAYS = 1;
    private static final int MAX_LOOKBACK_DAYS = 90;
    private static final Duration PROJECTION_LEASE_TTL = Duration.ofMinutes(15);

    private final InventoryLedgerQueryPort ledgerQueryPort;
    private final InventoryProjectionStore projectionStore;
    private final RetailProperties retailProperties;
    private final InventoryMetrics metrics;
    private final RunLease runLease;
    private final Clock clock;

    public InventoryProjectionService(InventoryLedgerQueryPort ledgerQueryPort,
                                      InventoryProjectionStore projectionStore,
                                      RetailProperties retailProperties,
                                      InventoryMetrics metrics,
                                      RunLease runLease,
                                      Clock clock) {
        this.ledgerQueryPort = ledgerQueryPort;
        this.projectionStore = projectionStore;
        this.retailProperties = retailProperties;
        this.metrics = metrics;
        this.runLease = runLease;
        this.clock = clock;
    }

    public ProjectionResult rebuild() {
        return withLease(this::rebuildInternal);
    }

    public ProjectionResult incremental(int lookbackDays) {
        return withLease(() -> incrementalInternal(lookbackDays));
    }

    private ProjectionResult rebuildInternal() {
        DateBounds ledger = ledgerQueryPort.ledgerBounds();
        // Always run the rebuild: WHEN NOT MATCHED BY SOURCE removes stale projection rows even
        // when the ledger is now empty.
        long affected = projectionStore.rebuild();
        metrics.recordProjection("FULL", affected);
        if (ledger.isEmpty()) {
            return result("NO_DATA", null, null, affected);
        }
        return result("REBUILT", ledger.earliest(), ledger.latest(), affected);
    }

    private ProjectionResult incrementalInternal(int lookbackDays) {
        DateBounds ledger = ledgerQueryPort.ledgerBounds();
        if (ledger.isEmpty()) {
            return result("NO_DATA", null, null, 0);
        }
        DateBounds projection = ledgerQueryPort.projectionBounds();
        if (projection.isEmpty() || projection.earliest().isAfter(ledger.earliest())) {
            return rebuildInternal();
        }

        int lookback = Math.min(Math.max(lookbackDays, MIN_LOOKBACK_DAYS), MAX_LOOKBACK_DAYS);
        LocalDate from = projection.latest().minusDays(lookback);
        LocalDate unprojected = ledgerQueryPort.earliestUnprojectedBusinessDate();
        if (unprojected != null && unprojected.isBefore(from)) {
            from = unprojected;
        }
        if (from.isBefore(ledger.earliest())) {
            from = ledger.earliest();
        }
        LocalDate to = ledger.latest();
        if (from.isAfter(to)) {
            return result("UP_TO_DATE", from, to, 0);
        }

        long affected = projectionStore.mergeRange(from, to);
        metrics.recordProjection("INCREMENTAL", affected);
        return result("INCREMENTAL", from, to, affected);
    }

    private ProjectionResult withLease(Supplier<ProjectionResult> action) {
        String key = "projection:" + retailProperties.dataset();
        String owner = UUID.randomUUID().toString();
        if (!runLease.tryAcquire(key, owner, PROJECTION_LEASE_TTL)) {
            throw new ConflictException("Inventory projection refresh is already running");
        }
        try {
            return action.get();
        } finally {
            runLease.release(key, owner);
        }
    }

    private ProjectionResult result(String status, LocalDate from, LocalDate to, long affected) {
        return new ProjectionResult(status, from, to, affected, clock.instant());
    }
}
