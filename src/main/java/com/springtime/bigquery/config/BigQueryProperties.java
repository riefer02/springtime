package com.springtime.bigquery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Tunables for the BigQuery client. Every value can be supplied through the corresponding
 * environment variable documented in {@code README.md}.
 */
@Validated
@ConfigurationProperties(prefix = "bigquery")
public record BigQueryProperties(

        /** Google Cloud project id. Required; set {@code GOOGLE_CLOUD_PROJECT}. */
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9:._-]{1,128}", message = "must be a GCP project id")
        String projectId,

        /** Default processing location for query jobs, e.g. {@code US} or {@code europe-west3}. */
        @DefaultValue("US") String location,

        /** Dataset used when a query references unqualified table names. Optional. */
        String defaultDataset,

        /** Hard ceiling for a single query's billed bytes. Defaults to 1 GiB. */
        @DefaultValue("1073741824") @Positive long maxBytesBilled,

        /** Hard ceiling for rows returned by a single API call. Defaults to 1000. */
        @DefaultValue("1000") @Positive @Max(100_000) long maxResults,

        /** Whether BigQuery's result cache may serve repeated queries. */
        @DefaultValue("true") boolean useQueryCache,

        /** When true (the default), only read-only statements are accepted by /api/v1/query. */
        @DefaultValue("true") boolean readOnly,

        /** Maximum number of in-flight BigQuery jobs across all callers. */
        @DefaultValue("8") @Positive int maxConcurrentQueries,

        /** How long a request waits for a free query slot before returning 429. */
        @DefaultValue("2s") Duration acquireTimeout,

        /** Server-side BigQuery job timeout; the job is cancelled after this duration. */
        @DefaultValue("60s") Duration queryTimeout
) {
}
