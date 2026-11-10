package com.springtime.bigquery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** On-hand projection refresh settings. The enabled flag is read by the scheduler condition. */
@Validated
@ConfigurationProperties(prefix = "retail.projection")
public record ProjectionProperties(
        @DefaultValue("900000") @Min(1_000) @Max(86_400_000) long intervalMs,
        @DefaultValue("7") @Min(1) @Max(90) int lookbackDays
) {
}
