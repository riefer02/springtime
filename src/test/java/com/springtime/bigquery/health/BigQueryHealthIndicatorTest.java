package com.springtime.bigquery.health;

import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.springtime.bigquery.gateway.BigQueryGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BigQueryHealthIndicatorTest {

    private final BigQueryGateway gateway = mock(BigQueryGateway.class);
    private final BigQueryHealthIndicator indicator = new BigQueryHealthIndicator(
            gateway, Clock.systemUTC());

    @Test
    void aDryRunWithoutAJobIdIsStillHealthy() {
        Job job = mock(Job.class);
        when(job.getJobId()).thenReturn(null);
        when(gateway.create(any(JobInfo.class))).thenReturn(job);

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("jobId", "unknown");
    }
}
