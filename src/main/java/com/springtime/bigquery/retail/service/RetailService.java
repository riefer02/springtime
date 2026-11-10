package com.springtime.bigquery.retail.service;

import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.DataNotReadyException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.model.InventoryPosition;
import com.springtime.bigquery.retail.model.ReplenishmentRecommendation;
import com.springtime.bigquery.retail.model.RetailDashboard;
import com.springtime.bigquery.retail.model.RetailPage;
import com.springtime.bigquery.retail.model.StoreSummary;
import com.springtime.bigquery.retail.model.RiskLevel;
import com.springtime.bigquery.retail.query.RetailQuery;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.util.RowValues;
import com.springtime.bigquery.model.ParameterType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class RetailService {

    private final RetailQueryPort queryPort;
    private final RetailProperties properties;
    private final Clock clock;

    public RetailService(RetailQueryPort queryPort, RetailProperties properties, Clock clock) {
        this.queryPort = queryPort;
        this.properties = properties;
        this.clock = clock;
    }

    public PageResponse<StoreSummary> stores(String pageToken, int pageSize) {
        int limit = properties.clamp(pageSize);
        int offset = PageToken.decodeOffset(pageToken);
        int fetch = limit + 1;
        RetailQueryResult result = queryPort.query(new RetailQuery(
                "stores",
                List.of(
                        param("row_limit", ParameterType.INT64, fetch),
                        param("row_offset", ParameterType.INT64, offset)),
                fetch));
        List<StoreSummary> fetched = result.rows().stream().map(RetailService::toStore).toList();
        return PageToken.page(fetched, offset, limit);
    }

    public RetailPage<InventoryPosition> inventory(
            String storeId, LocalDate asOfDate, String category, RiskLevel riskLevel, int pageSize) {
        requireStore(storeId);
        int limit = properties.clamp(pageSize);
        int fetch = limit + 1;
        LocalDate asOf = resolveDate(asOfDate);

        RetailQueryResult result = queryPort.query(new RetailQuery(
                "inventory_risk",
                List.of(
                        param("store_id", ParameterType.STRING, storeId),
                        param("as_of_date", ParameterType.DATE, asOf.toString()),
                        param("demand_days", ParameterType.INT64, properties.demandWindowDays()),
                        param("category", ParameterType.STRING, category == null ? "" : category.trim()),
                        param("risk_level", ParameterType.STRING,
                                riskLevel == null ? "" : riskLevel.name()),
                        param("row_limit", ParameterType.INT64, fetch)),
                fetch));

        List<InventoryPosition> fetched = result.rows().stream()
                .map(RetailService::toInventoryPosition)
                .toList();
        if (fetched.isEmpty() && !queryPort.storeExists(storeId)) {
            throw new ResourceNotFoundException("Store " + storeId + " was not found");
        }
        boolean truncated = fetched.size() > limit;
        List<InventoryPosition> items = truncated ? fetched.subList(0, limit) : fetched;
        return new RetailPage<>(items, asOf, limit, truncated);
    }

    public RetailPage<ReplenishmentRecommendation> replenishment(
            String storeId, LocalDate asOfDate, int pageSize) {
        requireStore(storeId);
        int limit = properties.clamp(pageSize);
        int fetch = limit + 1;
        LocalDate asOf = resolveDate(asOfDate);

        RetailQueryResult result = queryPort.query(new RetailQuery(
                "replenishment",
                List.of(
                        param("store_id", ParameterType.STRING, storeId),
                        param("as_of_date", ParameterType.DATE, asOf.toString()),
                        param("demand_days", ParameterType.INT64, properties.demandWindowDays()),
                        param("target_cover_days", ParameterType.INT64, properties.targetCoverDays()),
                        param("row_limit", ParameterType.INT64, fetch)),
                fetch));

        List<ReplenishmentRecommendation> fetched = result.rows().stream()
                .map(RetailService::toRecommendation)
                .toList();
        if (fetched.isEmpty() && !queryPort.storeExists(storeId)) {
            throw new ResourceNotFoundException("Store " + storeId + " was not found");
        }
        boolean truncated = fetched.size() > limit;
        List<ReplenishmentRecommendation> items = truncated ? fetched.subList(0, limit) : fetched;
        return new RetailPage<>(items, asOf, limit, truncated);
    }

    public RetailDashboard dashboard(String storeId, LocalDate asOfDate) {
        requireStore(storeId);
        LocalDate asOf = resolveDate(asOfDate);

        RetailQueryResult result = queryPort.query(new RetailQuery(
                "dashboard_summary",
                List.of(
                        param("store_id", ParameterType.STRING, storeId),
                        param("as_of_date", ParameterType.DATE, asOf.toString())),
                1));

        if (result.rows().isEmpty()) {
            throw new DataNotReadyException("No dashboard data for store " + storeId);
        }
        Map<String, Object> row = result.rows().get(0);
        if (RowValues.longValue(row, "store_exists") == 0) {
            throw new ResourceNotFoundException("Store " + storeId + " was not found");
        }
        return toDashboard(row);
    }

    private LocalDate resolveDate(LocalDate requested) {
        LocalDate date = requested != null ? requested : queryPort.latestSnapshotDate();
        if (date == null) {
            throw new DataNotReadyException("No inventory snapshot is available yet");
        }
        if (date.isAfter(properties.today(clock))) {
            throw new InvalidQueryException("asOfDate must not be in the future");
        }
        return date;
    }

    private static void requireStore(String storeId) {
        if (!StringUtils.hasText(storeId)) {
            throw new InvalidQueryException("storeId is required");
        }
    }

    private static QueryParameter param(String name, ParameterType type, Object value) {
        return new QueryParameter(name, type, value);
    }

    private static StoreSummary toStore(Map<String, Object> row) {
        return new StoreSummary(
                RowValues.string(row, "store_id"),
                RowValues.string(row, "store_name"),
                RowValues.string(row, "region"),
                RowValues.string(row, "city"),
                RowValues.string(row, "state"),
                RowValues.string(row, "time_zone"),
                RowValues.string(row, "dc_id"),
                RowValues.string(row, "store_format"));
    }

    private static InventoryPosition toInventoryPosition(Map<String, Object> row) {
        return new InventoryPosition(
                RowValues.string(row, "store_id"),
                RowValues.string(row, "store_name"),
                RowValues.string(row, "region"),
                RowValues.string(row, "product_id"),
                RowValues.string(row, "sku"),
                RowValues.string(row, "product_name"),
                RowValues.string(row, "category"),
                RowValues.date(row, "snapshot_date"),
                RowValues.longValue(row, "on_hand_units"),
                RowValues.longValue(row, "in_transit_units"),
                RowValues.longValue(row, "reserved_units"),
                RowValues.longValue(row, "available_units"),
                RowValues.longValue(row, "safety_stock_units"),
                RowValues.longValue(row, "reorder_point_units"),
                RowValues.doubleValueOrZero(row, "avg_daily_units"),
                RowValues.doubleValue(row, "days_of_supply"),
                RowValues.longValue(row, "lead_time_days"),
                RowValues.string(row, "risk_level"),
                RowValues.date(row, "projected_stockout_date"),
                RowValues.string(row, "unit_cost"),
                RowValues.string(row, "inventory_value"));
    }

    private static ReplenishmentRecommendation toRecommendation(Map<String, Object> row) {
        return new ReplenishmentRecommendation(
                RowValues.string(row, "store_id"),
                RowValues.string(row, "store_name"),
                RowValues.string(row, "product_id"),
                RowValues.string(row, "sku"),
                RowValues.string(row, "product_name"),
                RowValues.string(row, "category"),
                RowValues.string(row, "supplier_id"),
                RowValues.string(row, "supplier_name"),
                RowValues.longValue(row, "available_units"),
                RowValues.doubleValueOrZero(row, "avg_daily_units"),
                RowValues.doubleValue(row, "days_of_supply"),
                RowValues.longValue(row, "lead_time_days"),
                RowValues.longValue(row, "effective_reorder_point"),
                RowValues.longValue(row, "recommended_units"),
                RowValues.string(row, "order_value"));
    }

    private static RetailDashboard toDashboard(Map<String, Object> row) {
        return new RetailDashboard(
                RowValues.string(row, "store_id"),
                RowValues.date(row, "as_of_date"),
                RowValues.longValue(row, "on_hand_units"),
                RowValues.string(row, "inventory_value"),
                RowValues.longValue(row, "stockout_skus"),
                RowValues.longValue(row, "low_stock_skus"),
                RowValues.longValue(row, "units_28d"),
                RowValues.string(row, "net_sales_28d"),
                RowValues.longValue(row, "open_late_shipments"),
                RowValues.longValue(row, "delivered_late_lines"),
                RowValues.longValue(row, "active_promotions"));
    }


}
