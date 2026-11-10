package com.springtime.bigquery.messaging;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Bounded retry policy for message processing and other transient operations. */
@Validated
@ConfigurationProperties(prefix = "messaging.retry")
public record RetryProperties(
        @DefaultValue("5") @Min(1) @Max(20) int maxAttempts,
        @DefaultValue("200ms") @NotNull Duration initialDelay,
        @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
        @DefaultValue("10s") @NotNull Duration maxDelay,
        @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax("1.0") double jitter
) {
}
