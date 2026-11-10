package com.springtime.bigquery.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BigQueryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void appliesDefaultsForOptionalSettings() {
        runner.withPropertyValues("bigquery.project-id=my-project").run(context -> {
            BigQueryProperties properties = context.getBean(BigQueryProperties.class);
            assertThat(properties.projectId()).isEqualTo("my-project");
            assertThat(properties.location()).isEqualTo("US");
            assertThat(properties.defaultDataset()).isNull();
            assertThat(properties.maxBytesBilled()).isEqualTo(1_073_741_824L);
            assertThat(properties.maxResults()).isEqualTo(1_000L);
            assertThat(properties.useQueryCache()).isTrue();
            assertThat(properties.readOnly()).isTrue();
            assertThat(properties.maxConcurrentQueries()).isEqualTo(8);
            assertThat(properties.acquireTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
            assertThat(properties.queryTimeout()).isEqualTo(java.time.Duration.ofSeconds(60));
        });
    }

    @Test
    void requiresProjectId() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsExplicitValues() {
        runner.withPropertyValues(
                        "bigquery.project-id=my-project",
                        "bigquery.location=europe-west3",
                        "bigquery.default-dataset=analytics",
                        "bigquery.max-bytes-billed=5000",
                        "bigquery.max-results=25",
                        "bigquery.use-query-cache=false",
                        "bigquery.read-only=false",
                        "bigquery.max-concurrent-queries=3",
                        "bigquery.acquire-timeout=500ms",
                        "bigquery.query-timeout=30s")
                .run(context -> {
                    BigQueryProperties properties = context.getBean(BigQueryProperties.class);
                    assertThat(properties.projectId()).isEqualTo("my-project");
                    assertThat(properties.location()).isEqualTo("europe-west3");
                    assertThat(properties.defaultDataset()).isEqualTo("analytics");
                    assertThat(properties.maxBytesBilled()).isEqualTo(5_000L);
                    assertThat(properties.maxResults()).isEqualTo(25L);
                    assertThat(properties.useQueryCache()).isFalse();
                    assertThat(properties.readOnly()).isFalse();
                    assertThat(properties.maxConcurrentQueries()).isEqualTo(3);
                    assertThat(properties.acquireTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
                    assertThat(properties.queryTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BigQueryProperties.class)
    static class TestConfiguration {
    }
}
