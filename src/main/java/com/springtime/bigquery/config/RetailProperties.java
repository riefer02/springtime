package com.springtime.bigquery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/** Retail domain configuration. The dataset is owned by the app, not supplied by callers. */
@Validated
@ConfigurationProperties(prefix = "retail")
public record RetailProperties(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_]{1,1024}", message = "must be a BigQuery dataset id")
        String dataset,
        @DefaultValue("50") @Min(1) @Max(500) int defaultPageSize,
        @DefaultValue("200") @Min(1) @Max(500) int maxPageSize,
        @DefaultValue("28") @Positive int demandWindowDays,
        @DefaultValue("14") @Positive int targetCoverDays,
        @DefaultValue("268435456") @Positive long maxBytesBilled,
        @DefaultValue("USD") @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO currency code")
        String currency,
        @DefaultValue("5m") @NotNull Duration eventMaxFutureSkew,
        @DefaultValue("365d") @NotNull Duration eventMaxAge,
        @DefaultValue("0") @PositiveOrZero long reconciliationTolerance,
        @DefaultValue("UTC") @NotNull ZoneId businessZone
) {
    public int clamp(int requested) {
        return Math.max(1, Math.min(requested, maxPageSize));
    }

    /** Today in the configured business calendar, not the JVM's default zone. */
    public LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(businessZone));
    }
}
