package com.springtime.bigquery.retail.service;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.model.InventoryPosition;
import com.springtime.bigquery.retail.model.ReplenishmentRecommendation;
import com.springtime.bigquery.retail.model.RetailPage;
import com.springtime.bigquery.retail.model.StoreSummary;
import com.springtime.bigquery.retail.query.RetailQuery;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetailServiceTest {

    @Mock
    private RetailQueryPort queryPort;

    private RetailService service;

    @BeforeEach
    void setUp() {
        service = new RetailService(queryPort,
                new RetailProperties("retail_demo", 50, 200, 28, 14, 268_435_456L,
                        "USD", java.time.Duration.ofMinutes(5), java.time.Duration.ofDays(365), 0, java.time.ZoneOffset.UTC),
                java.time.Clock.fixed(java.time.Instant.parse("2026-11-09T12:00:00Z"),
                        java.time.ZoneOffset.UTC));
    }

    @Test
    void pagesStoresAndReturnsATokenOnlyWhenAnotherPageExists() {
        when(queryPort.query(any(RetailQuery.class))).thenReturn(
                new RetailQueryResult(List.of(storeRow("ST-001"), storeRow("ST-002"))),
                new RetailQueryResult(List.of(storeRow("ST-002"))));

        PageResponse<StoreSummary> first = service.stores(null, 1);

        assertThat(first.items()).extracting(StoreSummary::storeId).containsExactly("ST-001");
        assertThat(first.pageSize()).isEqualTo(1);
        assertThat(PageToken.decodeOffset(first.nextPageToken())).isEqualTo(1);

        PageResponse<StoreSummary> second = service.stores(first.nextPageToken(), 1);

        assertThat(second.items()).extracting(StoreSummary::storeId).containsExactly("ST-002");
        assertThat(second.nextPageToken()).isNull();

        ArgumentCaptor<RetailQuery> captor = ArgumentCaptor.forClass(RetailQuery.class);
        verify(queryPort, org.mockito.Mockito.times(2)).query(captor.capture());
        assertThat(captor.getAllValues().get(0).parameters())
                .extracting("name")
                .containsExactly("row_limit", "row_offset");
        assertThat(captor.getAllValues().get(0).parameters())
                .extracting("value")
                .containsExactly(2, 0);
        assertThat(captor.getAllValues().get(1).parameters())
                .extracting("value")
                .containsExactly(2, 1);
    }

    @Test
    void rejectsAMalformedStorePageToken() {
        assertThatThrownBy(() -> service.stores("not-a-token", 50))
                .isInstanceOf(com.springtime.bigquery.error.InvalidRequestException.class);
    }

    @Test
    void reportsMissingSnapshotData() {
        when(queryPort.latestSnapshotDate()).thenReturn(null);

        assertThatThrownBy(() -> service.inventory("ST-001", null, null, null, 50))
                .isInstanceOf(com.springtime.bigquery.error.DataNotReadyException.class)
                .hasMessageContaining("No inventory snapshot");
    }

    @Test
    void returnsAnUnknownStoreAsNotFoundRatherThanZeroedKpis() {
        when(queryPort.query(any(RetailQuery.class))).thenReturn(new RetailQueryResult(
                List.of(dashboardRow(0))));

        assertThatThrownBy(() -> service.dashboard("ST-999", LocalDate.of(2026, 11, 9)))
                .isInstanceOf(com.springtime.bigquery.error.ResourceNotFoundException.class)
                .hasMessageContaining("ST-999");
    }

    @Test
    void mapsAKnownStoreDashboard() {
        when(queryPort.query(any(RetailQuery.class))).thenReturn(new RetailQueryResult(
                List.of(dashboardRow(1))));

        var dashboard = service.dashboard("ST-001", LocalDate.of(2026, 11, 9));

        assertThat(dashboard.onHandUnits()).isEqualTo(42L);
        assertThat(dashboard.activePromotions()).isEqualTo(1L);
    }

    @Test
    void rejectsAFutureInventoryDate() {
        assertThatThrownBy(() -> service.inventory(
                "ST-001", LocalDate.of(2026, 11, 10), null, null, 50))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("future");
    }

    @Test
    void unknownStoreIsNotFoundForInventoryAndReplenishment() {
        when(queryPort.latestSnapshotDate()).thenReturn(LocalDate.of(2026, 11, 9));
        when(queryPort.query(any(RetailQuery.class))).thenReturn(new RetailQueryResult(List.of()));
        when(queryPort.storeExists("ST-404")).thenReturn(false);

        assertThatThrownBy(() -> service.inventory("ST-404", null, null, null, 50))
                .isInstanceOf(com.springtime.bigquery.error.ResourceNotFoundException.class)
                .hasMessageContaining("was not found");
        assertThatThrownBy(() -> service.replenishment("ST-404", null, 50))
                .isInstanceOf(com.springtime.bigquery.error.ResourceNotFoundException.class)
                .hasMessageContaining("was not found");
    }

    @Test
    void resolvesTheLatestSnapshotAndMapsInventoryRows() {
        when(queryPort.latestSnapshotDate()).thenReturn(LocalDate.of(2026, 11, 9));
        when(queryPort.query(any(RetailQuery.class))).thenReturn(new RetailQueryResult(List.of(row(
                "store_id", "ST-001",
                "store_name", "Store 01",
                "region", "Northeast",
                "product_id", "SKU-0001",
                "sku", "SKU-0001",
                "product_name", "Coffee 1",
                "category", "Beverages",
                "snapshot_date", "2026-11-09",
                "on_hand_units", 42L,
                "in_transit_units", 12L,
                "reserved_units", 2L,
                "available_units", 52L,
                "safety_stock_units", 20L,
                "reorder_point_units", 60L,
                "avg_daily_units", 4.2,
                "days_of_supply", 12.3,
                "lead_time_days", 5L,
                "risk_level", "WATCH",
                "projected_stockout_date", "2026-11-21",
                "unit_cost", "4.25",
                "inventory_value", "178.50"
        ))));

        RetailPage<InventoryPosition> page = service.inventory("ST-001", null, null, null, 500);

        assertThat(page.asOfDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(page.pageSize()).isEqualTo(200);
        assertThat(page.items()).hasSize(1);
        InventoryPosition item = page.items().get(0);
        assertThat(item.sku()).isEqualTo("SKU-0001");
        assertThat(item.availableUnits()).isEqualTo(52L);
        assertThat(item.inventoryValue()).isEqualTo("178.50");
        assertThat(item.projectedStockoutDate()).isEqualTo(LocalDate.of(2026, 11, 21));

        ArgumentCaptor<RetailQuery> captor = ArgumentCaptor.forClass(RetailQuery.class);
        verify(queryPort).query(captor.capture());
        assertThat(captor.getValue().sqlName()).isEqualTo("inventory_risk");
        assertThat(captor.getValue().parameters())
                .extracting("name")
                .contains("store_id", "as_of_date", "demand_days", "category", "risk_level", "row_limit");
    }

    @Test
    void mapsReplenishmentRows() {
        when(queryPort.latestSnapshotDate()).thenReturn(LocalDate.of(2026, 11, 9));
        when(queryPort.query(any(RetailQuery.class))).thenReturn(new RetailQueryResult(List.of(row(
                "store_id", "ST-001",
                "store_name", "Store 01",
                "product_id", "SKU-0001",
                "sku", "SKU-0001",
                "product_name", "Coffee 1",
                "category", "Beverages",
                "supplier_id", "SUP-001",
                "supplier_name", "Supplier 1",
                "available_units", 52L,
                "avg_daily_units", 4.2,
                "days_of_supply", 12.3,
                "lead_time_days", 5L,
                "effective_reorder_point", 60L,
                "recommended_units", 24L,
                "order_value", "102.00"
        ))));

        RetailPage<ReplenishmentRecommendation> page = service.replenishment("ST-001", null, 50);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).recommendedUnits()).isEqualTo(24L);
        assertThat(page.items().get(0).orderValue()).isEqualTo("102.00");
    }

    @Test
    void rejectsBlankStore() {
        assertThatThrownBy(() -> service.inventory(" ", null, null, null, 50))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("storeId");
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> storeRow(String storeId) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("store_id", storeId);
        row.put("store_name", "Store " + storeId);
        row.put("region", "Northeast");
        row.put("city", "Boston");
        row.put("state", "MA");
        row.put("time_zone", "America/New_York");
        row.put("dc_id", "DC-001");
        row.put("store_format", "URBAN");
        return row;
    }

    private static Map<String, Object> dashboardRow(long storeExists) {        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("store_id", "ST-001");
        row.put("as_of_date", "2026-11-09");
        row.put("on_hand_units", 42L);
        row.put("inventory_value", "178.50");
        row.put("stockout_skus", 0L);
        row.put("low_stock_skus", 1L);
        row.put("units_28d", 10L);
        row.put("net_sales_28d", "99.00");
        row.put("open_late_shipments", 1L);
        row.put("delivered_late_lines", 0L);
        row.put("active_promotions", 1L);
        row.put("store_exists", storeExists);
        return row;
    }

}
