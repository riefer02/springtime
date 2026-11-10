package com.springtime.bigquery.service;

import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Schema;
import com.springtime.bigquery.config.AppPaginationProperties;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.gateway.BigQueryGateway;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.model.query.DatasetSummary;
import com.springtime.bigquery.model.query.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogServiceTest {

    private final BigQueryGateway gateway = mock(BigQueryGateway.class);
    private final CatalogService service = new CatalogService(
            gateway,
            new BigQueryProperties("test-project", "US", "", 1_073_741_824L, 1_000,
                    true, true, 8, Duration.ofSeconds(2), Duration.ofSeconds(60)),
            new AppPaginationProperties(100, 1_000),
            new QueryResultMapper());

    @Test
    void paginatesDatasetsWithAServerCeiling() {
        Dataset dataset = mock(Dataset.class);
        when(dataset.getDatasetId()).thenReturn(DatasetId.of("test-project", "analytics"));
        when(dataset.getFriendlyName()).thenReturn("Analytics");
        when(dataset.getLocation()).thenReturn("US");

        @SuppressWarnings("unchecked")
        Page<Dataset> page = mock(Page.class);
        when(page.getValues()).thenReturn(List.of(dataset));
        when(page.getNextPageToken()).thenReturn("next-page");
        when(gateway.listDatasets(eq("test-project"), eq(1_000L), isNull())).thenReturn(page);

        PageResponse<DatasetSummary> response = service.listDatasets(null, 5_000);

        assertThat(response.pageSize()).isEqualTo(1_000);
        assertThat(response.nextPageToken()).isEqualTo("next-page");
        assertThat(response.items()).containsExactly(
                new DatasetSummary("test-project", "analytics", "Analytics", "US"));
    }

    @Test
    void describesNestedAndDuplicateColumns() {
        Schema schema = Schema.of(
                Field.of("x", LegacySQLTypeName.INTEGER),
                Field.of("x", LegacySQLTypeName.INTEGER),
                Field.of("person", LegacySQLTypeName.RECORD,
                        Field.of("name", LegacySQLTypeName.STRING)));

        List<Column> columns = new QueryResultMapper().columns(schema);

        assertThat(columns).extracting(Column::name).containsExactly("x", "x_2", "person");
        assertThat(columns.get(2).subFields()).extracting(Column::name).containsExactly("name");
    }
}
