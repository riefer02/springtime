package com.springtime.bigquery.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Bounds for controlled BigQuery DML; separate from the read/query budget. */
@Validated
@ConfigurationProperties(prefix = "bigquery.writer")
public record BigQueryWriterProperties(
        @DefaultValue("1073741824") @Positive long maxBytesBilled,
        @DefaultValue("4") @Positive int maxConcurrentQueries,
        @DefaultValue("2s") @NotNull Duration acquireTimeout
) {
}
