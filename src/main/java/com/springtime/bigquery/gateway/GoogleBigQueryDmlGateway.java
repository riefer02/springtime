package com.springtime.bigquery.gateway;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.BigQueryWriterProperties;
import com.springtime.bigquery.error.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** SDK adapter for controlled DML. The caller owns retry, idempotency, and error mapping. */
@Component
public class GoogleBigQueryDmlGateway implements BigQueryDmlGateway {

    private final BigQuery bigQuery;
    private final BigQueryProperties properties;
    private final BigQueryWriterProperties writerProperties;
    private final Semaphore writerSlots;

    public GoogleBigQueryDmlGateway(BigQuery bigQuery, BigQueryProperties properties,
                                    BigQueryWriterProperties writerProperties) {
        this.bigQuery = bigQuery;
        this.properties = properties;
        this.writerProperties = writerProperties;
        this.writerSlots = new Semaphore(writerProperties.maxConcurrentQueries(), true);
    }

    @Override
    public long execute(String sql, Map<String, QueryParameterValue> parameters, Map<String, String> labels) {
        Job job = run(sql, parameters, labels);
        Job completed = bigQuery.getJob(job.getJobId());
        JobStatistics.QueryStatistics statistics = asQueryStatistics(
                completed == null ? job.getStatistics() : completed.getStatistics());
        Long affectedRows = statistics == null ? null : statistics.getNumDmlAffectedRows();
        if (affectedRows == null) {
            throw new IllegalStateException("Controlled DML completed without affected-row statistics");
        }
        return affectedRows;
    }

    @Override
    public long executeIdempotent(String sql, Map<String, QueryParameterValue> parameters,
                                  Map<String, String> labels) {
        Job job = run(sql, parameters, labels);
        Job completed = bigQuery.getJob(job.getJobId());
        JobStatistics.QueryStatistics statistics = asQueryStatistics(
                completed == null ? job.getStatistics() : completed.getStatistics());
        Long affectedRows = statistics == null ? null : statistics.getNumDmlAffectedRows();
        return affectedRows == null ? 0L : affectedRows;
    }

    @Override
    public void executeTransaction(String sql, Map<String, QueryParameterValue> parameters,
                                   Map<String, String> labels) {
        run(sql, parameters, labels);
    }

    private Job run(String sql, Map<String, QueryParameterValue> parameters,
                    Map<String, String> labels) {
        acquireWriterSlot();
        try {
            return runJob(sql, parameters, labels);
        } finally {
            writerSlots.release();
        }
    }

    private Job runJob(String sql, Map<String, QueryParameterValue> parameters,
                       Map<String, String> labels) {
        QueryJobConfiguration.Builder builder = QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setLabels(labels)
                .setJobTimeoutMs(properties.queryTimeout().toMillis())
                .setMaximumBytesBilled(writerProperties.maxBytesBilled());
        if (parameters != null && !parameters.isEmpty()) {
            builder.setParameterMode("NAMED").setNamedParameters(parameters);
        }
        QueryJobConfiguration configuration = builder.build();

        Job job = bigQuery.create(JobInfo.newBuilder(configuration)
                .setJobId(JobId.of(UUID.randomUUID().toString()))
                .build());
        try {
            job.getQueryResults();
            return job;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelQuietly(job);
            throw new IllegalStateException("Interrupted while executing controlled DML", exception);
        } catch (RuntimeException exception) {
            cancelQuietly(job);
            throw exception;
        }
    }

    private void acquireWriterSlot() {
        try {
            if (!writerSlots.tryAcquire(
                    writerProperties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TooManyRequestsException(
                        "Too many concurrent BigQuery writes; retry shortly");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a BigQuery write slot",
                    exception);
        }
    }

    private static JobStatistics.QueryStatistics asQueryStatistics(JobStatistics statistics) {
        return statistics instanceof JobStatistics.QueryStatistics queryStatistics ? queryStatistics : null;
    }

    private static void cancelQuietly(Job job) {
        try {
            job.cancel();
        } catch (RuntimeException ignored) {
            // Best effort only; the original failure is more useful.
        }
    }
}
