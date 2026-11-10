package com.springtime.bigquery.retail.inventory.sink;

import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BigQueryInventoryProjectionStoreTest {

    private final BigQueryDmlGateway dml = mock(BigQueryDmlGateway.class);
    private final RetailSqlCatalog catalog = mock(RetailSqlCatalog.class);
    private final BigQueryInventoryProjectionStore store =
            new BigQueryInventoryProjectionStore(dml, catalog);

    @Test
    void rebuildUsesTheCatalogSql() {
        when(catalog.sql("inventory_projection_rebuild")).thenReturn("MERGE rebuild");
        when(dml.execute(eq("MERGE rebuild"), anyMap(), anyMap())).thenReturn(7L);

        assertThat(store.rebuild()).isEqualTo(7);
        verify(dml).execute(eq("MERGE rebuild"), eq(Map.of()), anyMap());
    }

    @Test
    void mergeRangeBindsTheWindow() {
        when(catalog.sql("inventory_projection_incremental")).thenReturn("MERGE incremental");
        when(dml.execute(eq("MERGE incremental"), anyMap(), anyMap())).thenReturn(3L);

        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 9);
        assertThat(store.mergeRange(from, to)).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, QueryParameterValue>> params = ArgumentCaptor.forClass(Map.class);
        verify(dml).execute(eq("MERGE incremental"), params.capture(), anyMap());
        assertThat(params.getValue().get("from_date").getValue()).isEqualTo("2026-11-01");
        assertThat(params.getValue().get("to_date").getValue()).isEqualTo("2026-11-09");
    }
}
