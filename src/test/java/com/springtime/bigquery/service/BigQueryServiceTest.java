package com.springtime.bigquery.service;

import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.TableResult;
import com.google.api.gax.paging.Page;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.model.query.DatasetSummary;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.QueryRequest;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.config.AppHttpProperties;
import com.springtime.bigquery.gateway.BigQueryGateway;
import com.springtime.bigquery.config.AppPaginationProperties;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.error.InvalidQueryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BigQueryServiceTest {

    private static final long MAX_RESULTS = 100L;
    private static final long MAX_BYTES = 1_024L;

    @Mock
    private BigQueryGateway gateway;

    @Mock
    private Job job;

    @Mock
    private JobStatistics.QueryStatistics statistics;

    private BigQueryService service;

    @BeforeEach
    void setUp() {
        BigQueryProperties properties = new BigQueryProperties(
                "test-project", "US", "test_dataset", MAX_BYTES, MAX_RESULTS, true, true,
                4, Duration.ofSeconds(1), Duration.ofSeconds(30));
        AppHttpProperties http = new AppHttpProperties(262_144, 100_000, 65_536, 100, 5_000_000, true);
        AppPaginationProperties pagination = new AppPaginationProperties(100, 1_000);
        service = new BigQueryService(
                gateway, properties, http, pagination, new SqlGuard(), new QueryResultMapper());
    }

    @Test
    void labelsInternalReadsWithTheirOwnSurface() {
        when(gateway.create(any(JobInfo.class))).thenReturn(job);
        when(job.getJobId()).thenReturn(JobId.of("job-labels"));
        when(job.getStatistics()).thenReturn(statistics);
        when(statistics.getTotalBytesProcessed()).thenReturn(0L);

        service.executeInternal("retail", new QueryRequest(
                "SELECT 1", List.of(), null, null, true, null));

        ArgumentCaptor<JobInfo> info = ArgumentCaptor.forClass(JobInfo.class);
        verify(gateway).create(info.capture());
        QueryJobConfiguration configuration =
                (QueryJobConfiguration) info.getValue().getConfiguration();
        assertThat(configuration.getLabels())
                .containsEntry("app", "springtime")
                .containsEntry("surface", "retail");
    }

    @Test
    void rejectsDmlWhenReadOnly() {
        assertThatThrownBy(() -> service.execute(new QueryRequest(
                "DELETE FROM `test-project.test_dataset.t`", List.of(), null, null, null, null)))
                .isInstanceOf(InvalidQueryException.class);

        verifyNoInteractions(gateway);
    }

    @Test
    void allowsDryRunWithoutReadOnlyCheck() {
        when(gateway.create(any(JobInfo.class))).thenReturn(job);
        when(job.getJobId()).thenReturn(JobId.of("job-1"));
        when(job.getStatistics()).thenReturn(statistics);
        when(statistics.getTotalBytesProcessed()).thenReturn(2_048L);

        QueryResponse response = service.execute(new QueryRequest(
                "DELETE FROM `test-project.test_dataset.t`", List.of(), null, null, true, null));

        assertThat(response.dryRun()).isTrue();
        assertThat(response.jobId()).isEqualTo("job-1");
        assertThat(response.bytesProcessed()).isEqualTo(2_048L);
        assertThat(response.rows()).isEmpty();
    }

    @Test
    void clampsClientLimitsAndCannotDisableServerCaching() {
        when(gateway.create(any(JobInfo.class))).thenReturn(job);
        when(job.getJobId()).thenReturn(JobId.of("job-2"));
        when(job.getStatistics()).thenReturn(statistics);
        when(statistics.getTotalBytesProcessed()).thenReturn(0L);

        service.execute(new QueryRequest("SELECT 1", List.of(), 5_000L, 10_000_000L, true, false));

        ArgumentCaptor<JobInfo> captor = ArgumentCaptor.forClass(JobInfo.class);
        verify(gateway).create(captor.capture());
        QueryJobConfiguration config = (QueryJobConfiguration) captor.getValue().getConfiguration();

        assertThat(config.getMaxResults()).isEqualTo(MAX_RESULTS);
        assertThat(config.getMaximumBytesBilled()).isEqualTo(MAX_BYTES);
        assertThat(config.useQueryCache()).isTrue();
        assertThat(config.getDefaultDataset()).isNotNull();
    }

    @Test
    void mapsTypedRowsAndStatistics() throws Exception {
        Field one = Field.of("one", LegacySQLTypeName.INTEGER);
        FieldValueList row = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1")), one);
        TableResult result = mock(TableResult.class);
        when(result.getSchema()).thenReturn(Schema.of(one));
        when(result.getValues()).thenReturn(List.of(row));
        when(result.getTotalRows()).thenReturn(1L);

        when(gateway.create(any(JobInfo.class))).thenReturn(job);
        when(job.getJobId()).thenReturn(JobId.of("job-3"));
        when(gateway.getQueryResults(any(Job.class), anyLong(), any())).thenReturn(result);
        when(gateway.getJob(any(JobId.class))).thenReturn(job);
        when(job.getStatistics()).thenReturn(statistics);
        when(statistics.getTotalBytesProcessed()).thenReturn(10L);
        when(statistics.getTotalBytesBilled()).thenReturn(10L);
        when(statistics.getCacheHit()).thenReturn(false);

        QueryResponse response = service.execute(new QueryRequest(
                "SELECT 1 AS one", List.of(), null, null, null, null));

        assertThat(response.rows()).containsExactly(Map.of("one", 1L));
        assertThat(response.schema()).extracting(Column::name).containsExactly("one");
        assertThat(response.returnedRows()).isEqualTo(1);
        assertThat(response.totalRows()).isEqualTo(1L);
        assertThat(response.bytesBilled()).isEqualTo(10L);
    }

    @Test
    void rejectsSqlLongerThanConfiguredLimit() {
        String sql = "SELECT " + "x".repeat(100_001);

        assertThatThrownBy(() -> service.execute(new QueryRequest(
                sql, List.of(), null, null, null, null)))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("at most 100000 characters");

        verifyNoInteractions(gateway);
    }
}
