package com.springtime.bigquery.retail.inventory.sink;

import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.inventory.application.InventoryProjectionStore;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.observability.TraceLabels;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** BigQuery adapter for the on-hand projection refresh. */
@Component
public class BigQueryInventoryProjectionStore implements InventoryProjectionStore {

    private final BigQueryDmlGateway dmlGateway;
    private final RetailSqlCatalog sqlCatalog;

    public BigQueryInventoryProjectionStore(BigQueryDmlGateway dmlGateway,
                                            RetailSqlCatalog sqlCatalog) {
        this.dmlGateway = dmlGateway;
        this.sqlCatalog = sqlCatalog;
    }

    @Override
    public long rebuild() {
        return dmlGateway.execute(
                sqlCatalog.sql("inventory_projection_rebuild"), Map.of(), labels());
    }

    @Override
    public long mergeRange(LocalDate from, LocalDate to) {
        return dmlGateway.execute(
                sqlCatalog.sql("inventory_projection_incremental"),
                Map.of(
                        "from_date", QueryParameterValue.date(from.toString()),
                        "to_date", QueryParameterValue.date(to.toString())),
                labels());
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("app", "springtime");
        labels.put("surface", "inventory-projection");
        TraceLabels.addTraceLabels(labels);
        return labels;
    }
}
