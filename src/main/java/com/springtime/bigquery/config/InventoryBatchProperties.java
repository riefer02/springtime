package com.springtime.bigquery.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Micro-batch settings for the inventory ledger sink. */
@Validated
@ConfigurationProperties(prefix = "retail.ledger.batch")
public record InventoryBatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") @Min(1) @Max(1000) int size,
        @DefaultValue("25") @Min(0) @Max(5000) long lingerMs,
        @DefaultValue("2000") @Min(1) int maxPending,
        @DefaultValue("33554432") @Min(1048576) long maxPendingBytes,
        @DefaultValue("75s") @NotNull Duration waitTimeout
) {
}
