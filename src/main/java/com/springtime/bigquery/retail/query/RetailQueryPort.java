package com.springtime.bigquery.retail.query;

import java.time.LocalDate;

/** Read-only port for retail SQL. Implementations may use BigQuery behind the shared query guard. */
public interface RetailQueryPort {

    RetailQueryResult query(RetailQuery query);

    /** Whether the store dimension contains this id. Used to separate 404 from an empty result. */
    boolean storeExists(String storeId);

    LocalDate latestSnapshotDate();

    /**
     * Latest business date with a ledger movement, or {@code null} when the ledger is empty.
     */
    LocalDate latestLedgerDate();
}
