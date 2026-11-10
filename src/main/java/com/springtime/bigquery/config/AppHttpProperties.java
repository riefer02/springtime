package com.springtime.bigquery.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Defensive HTTP/query limits. All values are tunable at deploy time. */
@Validated
@ConfigurationProperties(prefix = "app.http")
public record AppHttpProperties(
        @DefaultValue("262144") @Positive long maxRequestBytes,
        @DefaultValue("100000") @Positive int maxSqlLength,
        @DefaultValue("65536") @Positive int maxParameterValueLength,
        @DefaultValue("100") @Positive int maxParameters,
        @DefaultValue("5000000") @Positive long maxResponseBytes,
        @DefaultValue("true") boolean accessLogEnabled
) {
}
