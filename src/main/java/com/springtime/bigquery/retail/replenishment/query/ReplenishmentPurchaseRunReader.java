package com.springtime.bigquery.retail.replenishment.query;

import com.springtime.bigquery.retail.purchase.application.PurchaseRun;
import com.springtime.bigquery.retail.purchase.application.PurchaseRunReader;
import com.springtime.bigquery.retail.replenishment.application.ReplenishmentRunRepository;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Adapts the replenishment run model to the purchase-owned run snapshot. The dependency points
 * from replenishment to purchase, never the other way.
 */
@Component
public class ReplenishmentPurchaseRunReader implements PurchaseRunReader {

    private final ReplenishmentRunRepository repository;

    public ReplenishmentPurchaseRunReader(ReplenishmentRunRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PurchaseRun> find(String runId) {
        return repository.find(runId).map(run -> new PurchaseRun(
                run.runId(), run.storeId(), run.status() == ReplenishmentRunStatus.COMPLETED));
    }
}
