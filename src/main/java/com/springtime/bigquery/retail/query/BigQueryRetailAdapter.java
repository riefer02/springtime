package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.error.DataNotReadyException;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.util.RowValues;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Read side of the retail domain. All SQL is fixed and named; callers supply only typed parameters.
 */
@Component
public class BigQueryRetailAdapter implements RetailQueryPort {

    private static final Duration DATE_CACHE_TTL = Duration.ofSeconds(30);
    private static final int DATE_QUERY_LIMIT = 1;

    private final RetailSqlExecutor executor;
    private final Clock clock;
    private volatile CachedDate latestSnapshot;
    private volatile CachedDate latestLedger;

    public BigQueryRetailAdapter(RetailSqlExecutor executor, Clock clock) {
        this.executor = executor;
        this.clock = clock;
    }

    @Override
    public RetailQueryResult query(RetailQuery query) {
        return executor.execute(query);
    }

    @Override
    public boolean storeExists(String storeId) {
        RetailQueryResult result = executor.execute(new RetailQuery(
                "store_exists",
                List.of(new QueryParameter("store_id", ParameterType.STRING, storeId)),
                DATE_QUERY_LIMIT));
        return !result.rows().isEmpty()
                && RowValues.longValue(result.rows().get(0), "store_count") > 0;
    }

    @Override
    public LocalDate latestSnapshotDate() {
        CachedDate cached = latestSnapshot;
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        RetailQueryResult result = executor.execute(
                new RetailQuery("latest_snapshot", List.of(), DATE_QUERY_LIMIT));
        if (result.rows().isEmpty()) {
            throw new DataNotReadyException("No inventory snapshots are loaded");
        }
        Object value = result.rows().get(0).get("latest_snapshot_date");
        if (value == null) {
            throw new DataNotReadyException("No inventory snapshots are loaded");
        }
        LocalDate date = LocalDate.parse(String.valueOf(value));
        latestSnapshot = new CachedDate(date, now.plus(DATE_CACHE_TTL));
        return date;
    }

    @Override
    public LocalDate latestLedgerDate() {
        CachedDate cached = latestLedger;
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        RetailQueryResult result = executor.execute(
                new RetailQuery("latest_ledger", List.of(), DATE_QUERY_LIMIT));
        LocalDate date = null;
        if (!result.rows().isEmpty()) {
            Object value = result.rows().get(0).get("latest_ledger_date");
            date = value == null ? null : LocalDate.parse(String.valueOf(value));
        }
        latestLedger = new CachedDate(date, now.plus(DATE_CACHE_TTL));
        return date;
    }

    private record CachedDate(LocalDate value, Instant expiresAt) {
    }
}
