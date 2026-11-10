package com.springtime.bigquery.health;

import com.springtime.bigquery.gateway.BigQueryGateway;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Verifies that credentials, project routing and the BigQuery API work by issuing a free dry run.
 * Results are cached briefly so health scraping does not create a job per probe.
 */
@Component("bigquery")
@ConditionalOnEnabledHealthIndicator("bigquery")
public class BigQueryHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(BigQueryHealthIndicator.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final String PROBE_SQL = "SELECT 1";

    private final BigQueryGateway gateway;
    private final Clock clock;

    private volatile Health cached;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public BigQueryHealthIndicator(BigQueryGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant now = clock.instant();
        Health snapshot = cached;
        if (snapshot != null && now.isBefore(cachedUntil)) {
            return snapshot;
        }
        synchronized (this) {
            if (cached != null && clock.instant().isBefore(cachedUntil)) {
                return cached;
            }
            Health result = probe();
            cached = result;
            cachedUntil = clock.instant().plus(CACHE_TTL);
            return result;
        }
    }

    private Health probe() {
        try {
            Job job = gateway.create(JobInfo.of(QueryJobConfiguration.newBuilder(PROBE_SQL)
                    .setDryRun(true)
                    .setUseLegacySql(false)
                    .build()));
            // A dry run may not return a job id; health details must never be null.
            String jobId = job == null || job.getJobId() == null || job.getJobId().getJob() == null
                    ? "unknown"
                    : job.getJobId().getJob();
            return Health.up()
                    .withDetail("probe", "dryRun")
                    .withDetail("jobId", jobId)
                    .build();
        } catch (RuntimeException exception) {
            log.warn("BigQuery health probe failed", exception);
            // Do not echo credential/exception text through an unauthenticated health endpoint.
            return Health.down()
                    .withDetail("probe", "dryRun")
                    .withDetail("error", exception.getClass().getSimpleName())
                    .build();
        }
    }
}
