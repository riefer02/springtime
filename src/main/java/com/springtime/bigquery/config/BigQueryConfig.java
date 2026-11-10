package com.springtime.bigquery.config;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
class BigQueryConfig {

    /**
     * The client is lazily created so the application (and its test slices) can start without
     * credentials. The first real call, or the readiness/health probe, materialises it and fails
     * loudly if ADC is missing.
     */
    @Bean
    @Lazy
    BigQuery bigQuery(BigQueryProperties properties) {
        return BigQueryOptions.newBuilder()
                .setProjectId(properties.projectId())
                .setLocation(properties.location())
                .build()
                .getService();
    }
}
