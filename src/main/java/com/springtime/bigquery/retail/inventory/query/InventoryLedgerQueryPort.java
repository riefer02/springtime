package com.springtime.bigquery.retail.inventory.query;

import com.springtime.bigquery.retail.inventory.model.DateBounds;
import com.springtime.bigquery.retail.inventory.model.InventoryDrift;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;

import java.time.LocalDate;
import java.util.List;

/** Read port for the ledger and its reconciliation view. */
public interface InventoryLedgerQueryPort {

    List<InventoryMovementView> movements(String storeId, String productId,
                                          LocalDate from, LocalDate to, int limit, int offset);

    List<InventoryDrift> reconciliation(String storeId, LocalDate asOfDate, long tolerance, int limit);

    DateBounds ledgerBounds();

    DateBounds projectionBounds();

    /** Earliest business date with a movement ingested after the last projection update. */
    LocalDate earliestUnprojectedBusinessDate();
}
