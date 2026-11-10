package com.springtime.bigquery.retail.replenishment.store;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ProposalStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;

import com.google.cloud.bigquery.QueryParameterValue;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BigQueryReplenishmentRunRepositoryTest {

    private final BigQueryDmlGateway dml = mock(BigQueryDmlGateway.class);
    private final RetailProperties retailProperties = new RetailProperties(
            "retail_ds", 50, 200, 28, 14, 1_000L, "USD",
            Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    private final BigQueryReplenishmentRunRepository repository =
            new BigQueryReplenishmentRunRepository(
                    dml, mock(RetailSqlExecutor.class),
                    new RetailSqlCatalog(new DefaultResourceLoader(),
                    new BigQueryProperties("project-x", "US", "", 1_000, 100, true, true, 2,
                            Duration.ofSeconds(1), Duration.ofSeconds(10)),
                    retailProperties));

    @Test
    void beginTruncatesNanosecondsThatBigQueryRejects() {
        Instant nanos = Instant.parse("2026-11-10T17:05:58.160876381Z");
        repository.begin(new ReplenishmentRun(
                "run-1", "ST-001", LocalDate.of(2026, 11, 9), ReplenishmentRunStatus.RUNNING,
                28, 14, "tester", nanos, nanos, null, null, null, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, QueryParameterValue>> params = ArgumentCaptor.forClass(Map.class);
        verify(dml).execute(anyString(), params.capture(), anyMap());
        assertThat(params.getValue().get("created_at").getValue())
                .isEqualTo("2026-11-10T17:05:58.160876Z");
        assertThat(params.getValue().get("started_at").getValue())
                .isEqualTo("2026-11-10T17:05:58.160876Z");
    }

    @Test
    void decidesInOneTransactionalScript() {
        repository.decideProposal(new ProposalDecision(
                "d-1", "run-1", "ST-001", "SKU-1",
                ProposalStatus.PROPOSED, ProposalStatus.APPROVED, 0,
                "planner", "ok", null, Instant.parse("2026-11-09T12:00:00Z")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(dml).executeTransaction(sql.capture(), anyMap(), anyMap());
        assertThat(sql.getValue())
                .contains("BEGIN TRANSACTION", "ASSERT (@@row_count = 1)",
                        "run is not accepting decisions", "COMMIT TRANSACTION");
    }
}
