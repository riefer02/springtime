package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.model.query.QueryRequest;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.service.BigQueryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetailSqlExecutorTest {

    private final BigQueryService queryService = mock(BigQueryService.class);
    private final RetailProperties retailProperties = new RetailProperties(
            "retail_ds", 50, 200, 28, 14, 1_000L, "USD",
            Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    private final RetailSqlExecutor executor = new RetailSqlExecutor(
            queryService,
            new RetailSqlCatalog(new DefaultResourceLoader(),
                    new BigQueryProperties("project-x", "US", "", 1_000, 100, true, true, 2,
                            Duration.ofSeconds(1), Duration.ofSeconds(10)),
                    retailProperties),
            retailProperties);

    @Test
    void substitutesProjectAndDatasetIntoFixedSql() {
        when(queryService.executeInternal(any(), any())).thenReturn(
                new QueryResponse("job-1", List.of(), List.of(), 0, null, 0, 0, false, false, null, false));

        executor.execute(new RetailQuery("stores", List.of(), 1));

        ArgumentCaptor<QueryRequest> request = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryService).executeInternal(org.mockito.ArgumentMatchers.eq("retail"), request.capture());
        assertThat(request.getValue().sql()).contains("project-x.retail_ds.stores");
    }

    @Test
    void rejectsAUnsafeSqlResourceName() {
        assertThatThrownBy(() -> executor.execute(new RetailQuery("../secrets", List.of(), 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
