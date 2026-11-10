package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.DataNotReadyException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.inventory.model.InventoryDrift;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;
import com.springtime.bigquery.retail.inventory.model.ReconciliationReport;
import com.springtime.bigquery.retail.inventory.query.InventoryLedgerQueryPort;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Application service for ledger reads and ledger-to-snapshot reconciliation. */
@Service
public class InventoryService {

    private final InventoryLedgerQueryPort ledgerQueryPort;
    private final RetailQueryPort retailQueryPort;
    private final RetailProperties properties;
    private final InventoryMetrics metrics;
    private final Clock clock;

    public InventoryService(InventoryLedgerQueryPort ledgerQueryPort,
                            RetailQueryPort retailQueryPort,
                            RetailProperties properties,
                            InventoryMetrics metrics,
                            Clock clock) {
        this.ledgerQueryPort = ledgerQueryPort;
        this.retailQueryPort = retailQueryPort;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public PageResponse<InventoryMovementView> movements(String storeId, String productId,
                                                          LocalDate from, LocalDate to,
                                                          String pageToken, int limit) {
        LocalDate end = to == null ? ledgerOrSnapshotDate() : to;
        if (end == null) {
            throw new DataNotReadyException("No ledger or snapshot data is available yet");
        }
        if (end.isAfter(properties.today(clock))) {
            throw new InvalidQueryException("to must not be in the future");
        }
        LocalDate start = from == null ? end.minusDays(7) : from;
        validateRange(start, end);
        int pageSize = properties.clamp(limit);
        int offset = PageToken.decodeOffset(pageToken);
        List<InventoryMovementView> fetched = ledgerQueryPort.movements(
                storeId, productId, start, end, pageSize + 1, offset);
        PageResponse<InventoryMovementView> page = PageToken.page(fetched, offset, pageSize);
        metrics.recordMovementsRead(page.items().size());
        return page;
    }

    public ReconciliationReport reconciliation(String storeId, LocalDate asOfDate,
                                                Long tolerance, int limit) {
        LocalDate asOf = asOfDate == null ? retailQueryPort.latestSnapshotDate() : asOfDate;
        if (asOfDate != null && asOfDate.isAfter(properties.today(clock))) {
            throw new InvalidQueryException("asOfDate must not be in the future");
        }
        long effectiveTolerance = tolerance == null
                ? properties.reconciliationTolerance()
                : Math.max(0, tolerance);
        int clamped = properties.clamp(limit);
        List<InventoryDrift> fetched =
                ledgerQueryPort.reconciliation(storeId, asOf, effectiveTolerance, clamped + 1);
        if (fetched.isEmpty() && storeId != null && !storeId.isBlank()
                && !retailQueryPort.storeExists(storeId)) {
            throw new com.springtime.bigquery.error.ResourceNotFoundException(
                    "Store " + storeId + " was not found");
        }
        boolean truncated = fetched.size() > clamped;
        List<InventoryDrift> items = truncated ? fetched.subList(0, clamped) : fetched;
        long maxAbsoluteDrift = items.stream()
                .mapToLong(item -> Math.abs(item.driftUnits()))
                .max()
                .orElse(0L);
        String status = items.isEmpty() ? "BALANCED" : "DRIFT";
        metrics.recordReconciliation(status, items.size());
        return new ReconciliationReport(
                status, asOf, effectiveTolerance, items.size(), clamped, truncated,
                maxAbsoluteDrift, clock.instant(), items);
    }

    private LocalDate ledgerOrSnapshotDate() {
        LocalDate ledgerDate = retailQueryPort.latestLedgerDate();
        return ledgerDate == null ? retailQueryPort.latestSnapshotDate() : ledgerDate;
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidQueryException("from must be before or equal to to");
        }
        if (from.isBefore(to.minusYears(10))) {
            throw new InvalidQueryException("date range must be at most 10 years");
        }
    }
}
