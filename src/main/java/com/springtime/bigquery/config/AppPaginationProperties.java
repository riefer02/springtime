package com.springtime.bigquery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Page-size defaults and the hard ceiling applied to every paginated endpoint. */
@Validated
@ConfigurationProperties(prefix = "app.pagination")
public record AppPaginationProperties(
        @DefaultValue("100") @Min(1) int defaultPageSize,
        @DefaultValue("1000") @Min(1) @Max(10_000) int maxPageSize
) {
    public int clamp(int requested) {
        return Math.max(1, Math.min(requested, maxPageSize));
    }
}
