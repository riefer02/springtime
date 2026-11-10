package com.springtime.bigquery.retail.inventory.query;

import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.retail.inventory.model.DateBounds;
import com.springtime.bigquery.retail.inventory.model.InventoryDrift;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;
import com.springtime.bigquery.retail.query.RetailQuery;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.util.RowValues;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/** BigQuery adapter for the inventory ledger read models. */
@Component
public class BigQueryInventoryLedgerAdapter implements InventoryLedgerQueryPort {

    private final RetailSqlExecutor executor;

    public BigQueryInventoryLedgerAdapter(RetailSqlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public List<InventoryMovementView> movements(String storeId, String productId,
                                                  LocalDate from, LocalDate to,
                                                  int limit, int offset) {
        RetailQueryResult result = executor.execute(new RetailQuery(
                "inventory_movements",
                List.of(
                        param("store_id", ParameterType.STRING, storeId == null ? "" : storeId),
                        param("product_id", ParameterType.STRING, productId == null ? "" : productId),
                        param("from_date", ParameterType.DATE, from.toString()),
                        param("to_date", ParameterType.DATE, to.toString()),
                        param("row_limit", ParameterType.INT64, limit),
                        param("row_offset", ParameterType.INT64, offset)),
                limit));
        return result.rows().stream()
                .map(BigQueryInventoryLedgerAdapter::toMovement)
                .toList();
    }

    @Override
    public List<InventoryDrift> reconciliation(String storeId, LocalDate asOfDate,
                                                long tolerance, int limit) {
        RetailQueryResult result = executor.execute(new RetailQuery(
                "inventory_reconciliation",
                List.of(
                        param("store_id", ParameterType.STRING, storeId == null ? "" : storeId),
                        param("as_of_date", ParameterType.DATE, asOfDate.toString()),
                        param("tolerance", ParameterType.INT64, tolerance),
                        param("row_limit", ParameterType.INT64, limit)),
                limit));
        return result.rows().stream()
                .map(BigQueryInventoryLedgerAdapter::toDrift)
                .toList();
    }

    @Override
    public DateBounds ledgerBounds() {
        return bounds("ledger_bounds");
    }

    @Override
    public DateBounds projectionBounds() {
        return bounds("projection_bounds");
    }

    @Override
    public LocalDate earliestUnprojectedBusinessDate() {
        RetailQueryResult result = executor.execute(
                new RetailQuery("projection_unprojected_from", List.of(), 1));
        if (result.rows().isEmpty()) {
            return null;
        }
        return RowValues.date(result.rows().get(0), "min_business_date");
    }

    private DateBounds bounds(String sqlName) {
        RetailQueryResult result = executor.execute(new RetailQuery(sqlName, List.of(), 1));
        if (result.rows().isEmpty()) {
            return DateBounds.empty();
        }
        var row = result.rows().get(0);
        return new DateBounds(
                RowValues.date(row, "min_date"),
                RowValues.date(row, "max_date"));
    }

    private static InventoryMovementView toMovement(java.util.Map<String, Object> row) {
        return new InventoryMovementView(
                RowValues.string(row, "event_id"),
                RowValues.string(row, "movement_type"),
                RowValues.instant(row, "occurred_at"),
                RowValues.date(row, "business_date"),
                RowValues.string(row, "store_id"),
                RowValues.string(row, "product_id"),
                RowValues.longValue(row, "quantity_delta"),
                RowValues.bigDecimal(row, "unit_cost"),
                RowValues.string(row, "currency"),
                RowValues.string(row, "source_system"),
                RowValues.string(row, "source_document_id"),
                RowValues.string(row, "schema_version"));
    }

    private static InventoryDrift toDrift(java.util.Map<String, Object> row) {
        return new InventoryDrift(
                RowValues.string(row, "store_id"),
                RowValues.string(row, "product_id"),
                RowValues.longValue(row, "ledger_units"),
                RowValues.longValue(row, "snapshot_units"),
                RowValues.longValue(row, "drift_units"));
    }

    private static QueryParameter param(String name, ParameterType type, Object value) {
        return new QueryParameter(name, type, value);
    }
}
