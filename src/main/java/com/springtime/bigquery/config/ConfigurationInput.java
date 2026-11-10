package com.springtime.bigquery.config;

import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.messaging.RetryProperties;

/** Inputs for cross-field startup validation. */
public record ConfigurationInput(
        BigQueryProperties bigQuery,
        AppHttpProperties http,
        AppPaginationProperties pagination,
        AppSecurityProperties security,
        RetailProperties retail,
        MessagingProperties messaging,
        RetryProperties retry,
        String oidcIssuerUri,
        String oidcAudiences,
        String oidcJwkSetUri,
        InventoryBatchProperties batch
) {

    /** Convenience for unit tests that only exercise the non-batch rules. */
    public ConfigurationInput(BigQueryProperties bigQuery,
                              AppHttpProperties http,
                              AppPaginationProperties pagination,
                              AppSecurityProperties security,
                              RetailProperties retail,
                              MessagingProperties messaging,
                              RetryProperties retry,
                              String oidcIssuerUri,
                              String oidcAudiences,
                              String oidcJwkSetUri) {
        this(bigQuery, http, pagination, security, retail, messaging, retry,
                oidcIssuerUri, oidcAudiences, oidcJwkSetUri,
                new InventoryBatchProperties(true, 100, 25, 2000, 33_554_432,
                        java.time.Duration.ofSeconds(75)));
    }
}
