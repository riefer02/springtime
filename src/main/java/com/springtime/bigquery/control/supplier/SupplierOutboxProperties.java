package com.springtime.bigquery.control.supplier;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Durable supplier outbox and worker settings. */
@Validated
@ConfigurationProperties(prefix = "control.supplier.outbox")
public record SupplierOutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("5000") @Min(100) long pollIntervalMs,
        @DefaultValue("20") @Min(1) @Max(200) int batchSize,
        @DefaultValue("8") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("1s") @NotNull Duration initialDelay,
        @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
        @DefaultValue("5m") @NotNull Duration maxDelay,
        @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax("1.0") double jitter
) {
}
