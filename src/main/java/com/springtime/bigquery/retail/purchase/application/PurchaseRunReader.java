package com.springtime.bigquery.retail.purchase.application;

import java.util.Optional;

/**
 * Purchase-owned port for run status. The replenishment adapter implements it, so purchase does not
 * import the replenishment run model.
 */
public interface PurchaseRunReader {

    Optional<PurchaseRun> find(String runId);
}
