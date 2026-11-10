package com.springtime.bigquery.service;

import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.model.query.QueryRequest;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.model.query.QueryResultPage;
import com.springtime.bigquery.config.AppHttpProperties;
import com.springtime.bigquery.gateway.BigQueryGateway;
import com.springtime.bigquery.config.AppPaginationProperties;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.error.TooManyRequestsException;
import com.springtime.bigquery.error.UpstreamUnavailableException;
import com.springtime.bigquery.observability.JobLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class BigQueryService {

    private static final Logger log = LoggerFactory.getLogger(BigQueryService.class);

    private final BigQueryGateway gateway;
    private final BigQueryProperties properties;
    private final AppHttpProperties httpProperties;
    private final AppPaginationProperties paginationProperties;
    private final SqlGuard sqlGuard;
    private final QueryResultMapper resultMapper;
    private final Semaphore querySlots;
    private final Semaphore retailQuerySlots;

    public BigQueryService(BigQueryGateway gateway,
                           BigQueryProperties properties,
                           AppHttpProperties httpProperties,
                           AppPaginationProperties paginationProperties,
                           SqlGuard sqlGuard,
                           QueryResultMapper resultMapper) {
        this.gateway = gateway;
        this.properties = properties;
        this.httpProperties = httpProperties;
        this.paginationProperties = paginationProperties;
        this.sqlGuard = sqlGuard;
        this.resultMapper = resultMapper;
        this.querySlots = new Semaphore(properties.maxConcurrentQueries(), true);
        // Retail reads get their own slots so a burst of slow operator queries cannot starve the
        // store-facing endpoints; retail keeps a smaller share of the same BigQuery quota.
        this.retailQuerySlots = new Semaphore(
                Math.max(1, properties.maxConcurrentQueries() / 2), true);
    }

    public QueryResponse execute(QueryRequest request) {
        return run(request, querySlots, "rest-api");
    }

    /**
     * Execute an internal read with its own concurrency pool and job-label surface. Used by the
     * retail adapters so retail traffic does not compete with operator {@code /query} calls.
     */
    public QueryResponse executeInternal(String surface, QueryRequest request) {
        return run(request, retailQuerySlots, surface);
    }

    private QueryResponse run(QueryRequest request, Semaphore slots, String surface) {
        if (request.sql().length() > httpProperties.maxSqlLength()) {
            throw new InvalidQueryException(
                    "sql must be at most %d characters".formatted(httpProperties.maxSqlLength()));
        }
        if (request.parameters().size() > httpProperties.maxParameters()) {
            throw new InvalidQueryException(
                    "at most %d parameters are allowed".formatted(httpProperties.maxParameters()));
        }

        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        if (properties.readOnly() && !dryRun) {
            sqlGuard.assertReadOnly(request.sql());
        }

        long maxResults = clamp(
                request.maxResults() == null ? properties.maxResults() : request.maxResults(),
                properties.maxResults());
        long maxBytesBilled = clamp(
                request.maximumBytesBilled() == null ? properties.maxBytesBilled() : request.maximumBytesBilled(),
                properties.maxBytesBilled());
        // A client may enable caching but can never disable a server-side caching policy.
        boolean useQueryCache = request.useQueryCache() == null
                ? properties.useQueryCache()
                : properties.useQueryCache() || request.useQueryCache();

        QueryJobConfiguration.Builder config = QueryJobConfiguration.newBuilder(request.sql())
                .setUseLegacySql(false)
                .setUseQueryCache(useQueryCache)
                .setMaximumBytesBilled(maxBytesBilled)
                .setMaxResults(maxResults)
                .setJobTimeoutMs(properties.queryTimeout().toMillis())
                .setDryRun(dryRun)
                .setLabels(jobLabels(surface));

        applyDefaultDataset(config);
        applyParameters(config, request.parameters());
        QueryJobConfiguration query = config.build();

        acquireQuerySlot(slots);
        try {
            Job job = createJob(query);
            String jobId = job.getJobId().getJob();
            MDC.put("jobId", jobId);
            try {
                if (dryRun) {
                    JobStatistics.QueryStatistics statistics = queryStatistics(job);
                    long bytes = valueOrZero(statistics.getTotalBytesProcessed());
                    log.info("dry-run job={} bytesProcessed={}", jobId, bytes);
                    return new QueryResponse(
                            jobId, List.of(), List.of(), 0, null, bytes, 0, false, true, null, false);
                }

                TableResult result = queryResults(job, maxResults, null);
                QueryResultMapper.QueryData data = resultMapper.map(
                        result, httpProperties.maxResponseBytes());

                // The job handle returned by create() is a snapshot; reload it to read final statistics.
                Job completed = gateway.getJob(job.getJobId());
                JobStatistics.QueryStatistics statistics = queryStatistics(completed == null ? job : completed);
                long bytesProcessed = valueOrZero(statistics.getTotalBytesProcessed());
                long bytesBilled = valueOrZero(statistics.getTotalBytesBilled());
                boolean cacheHit = Boolean.TRUE.equals(statistics.getCacheHit());
                log.info("query job={} rows={} bytesProcessed={} bytesBilled={} cacheHit={}",
                        jobId, data.rows().size(), bytesProcessed, bytesBilled, cacheHit);

                return new QueryResponse(
                        jobId,
                        data.schema(),
                        data.rows(),
                        data.rows().size(),
                        result.getTotalRows(),
                        bytesProcessed,
                        bytesBilled,
                        cacheHit,
                        false,
                        data.truncated() ? null : result.getNextPageToken(),
                        data.truncated());
            } finally {
                MDC.remove("jobId");
            }
        } finally {
            slots.release();
        }
    }

    public QueryResultPage queryResults(String jobId, String pageToken, int pageSize) {
        int size = paginationProperties.clamp(pageSize);
        JobId id = JobId.of(properties.projectId(), jobId);
        Job job = gateway.getJob(id);
        if (job == null) {
            throw new ResourceNotFoundException("Job %s was not found".formatted(jobId));
        }

        TableResult result = queryResults(job, size, pageToken);
        QueryResultMapper.QueryData data = resultMapper.map(
                        result, httpProperties.maxResponseBytes());
        return new QueryResultPage(
                jobId,
                data.schema(),
                data.rows(),
                data.rows().size(),
                result.getTotalRows(),
                data.truncated() ? null : result.getNextPageToken(),
                data.truncated());
    }

    private void acquireQuerySlot(Semaphore slots) {
        try {
            if (!slots.tryAcquire(properties.acquireTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TooManyRequestsException("Too many concurrent queries; retry shortly");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("Interrupted while waiting for a query slot", exception);
        }
    }

    private Job createJob(QueryJobConfiguration config) {
        JobId jobId = JobId.of(UUID.randomUUID().toString());
        JobInfo jobInfo = JobInfo.newBuilder(config).setJobId(jobId).build();
        return gateway.create(jobInfo);
    }

    private TableResult queryResults(Job job, long maxResults, String pageToken) {
        try {
            return gateway.getQueryResults(job, maxResults, pageToken);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancelQuietly(job);
            throw new UpstreamUnavailableException(
                    "Interrupted while waiting for BigQuery job " + job.getJobId().getJob(), exception);
        }
    }

    private static void cancelQuietly(Job job) {
        try {
            job.cancel();
        } catch (RuntimeException exception) {
            log.warn("Unable to cancel BigQuery job {}", job.getJobId(), exception);
        }
    }

    private JobStatistics.QueryStatistics queryStatistics(Job job) {
        JobStatistics.QueryStatistics statistics = asQueryStatistics(job.getStatistics());
        if (statistics != null && statistics.getTotalBytesProcessed() != null) {
            return statistics;
        }
        try {
            Job reloaded = gateway.getJob(job.getJobId());
            JobStatistics.QueryStatistics reloadedStatistics =
                    reloaded == null ? null : asQueryStatistics(reloaded.getStatistics());
            if (reloadedStatistics != null) {
                return reloadedStatistics;
            }
        } catch (RuntimeException exception) {
            // Telemetry lookup is best-effort; never fail an already successful query for it.
            log.warn("Unable to reload statistics for BigQuery job {}", job.getJobId(), exception);
        }
        if (statistics != null) {
            return statistics;
        }
        throw new IllegalStateException(
                "BigQuery job " + job.getJobId().getJob() + " did not return query statistics");
    }

    private static JobStatistics.QueryStatistics asQueryStatistics(JobStatistics statistics) {
        return statistics instanceof JobStatistics.QueryStatistics queryStatistics ? queryStatistics : null;
    }

    private void applyDefaultDataset(QueryJobConfiguration.Builder config) {
        if (StringUtils.hasText(properties.defaultDataset())) {
            config.setDefaultDataset(DatasetId.of(properties.projectId(), properties.defaultDataset()));
        }
    }

    private void applyParameters(QueryJobConfiguration.Builder config, List<QueryParameter> parameters) {
        if (!parameters.isEmpty()) {
            config.setNamedParameters(QueryParameterBinder.bind(
                            parameters, httpProperties.maxParameterValueLength()))
                    .setParameterMode("NAMED");
        }
    }

    private static Map<String, String> jobLabels(String surface) {
        return JobLabels.of(surface);
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static long clamp(long requested, long ceiling) {
        return Math.max(1L, Math.min(requested, ceiling));
    }
}
