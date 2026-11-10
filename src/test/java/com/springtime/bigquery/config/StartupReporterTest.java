package com.springtime.bigquery.config;

import com.springtime.bigquery.control.supplier.SupplierOutboxProperties;
import com.springtime.bigquery.messaging.MessagingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StartupReporterTest {

    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.url",
                    "jdbc:postgresql://postgres:5432/springtime?user=springtime&password=supersecret")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("control.supplier.gateway", "local")
            .withProperty("retail.projection.enabled", "false")
            .withProperty("springdoc.api-docs.enabled", "false")
            .withProperty("management.tracing.enabled", "true")
            .withProperty("management.opentelemetry.tracing.export.otlp.endpoint",
                    "http://localhost:4318/v1/traces");

    private final StartupReporter reporter = new StartupReporter(
            new BigQueryProperties("demo-project", "US", "analytics", 1_000_000, 1_000,
                    true, true, 8, Duration.ofSeconds(2), Duration.ofSeconds(60)),
            new RetailProperties("retail_demo", 50, 200, 28, 14, 1_000_000, "USD",
                    Duration.ofMinutes(5), Duration.ofDays(365), 0, ZoneOffset.UTC),
            new MessagingProperties(false, "retail-events", "retail-events-dlq",
                    "retail-events-worker", true, true, 500, 1_000),
            new AppSecurityProperties("a".repeat(32), "", false, "roles", List.of(), List.of(),
                    false, ""),
            new SupplierOutboxProperties(true, true, 5_000, 20, 8,
                    Duration.ofSeconds(1), 2.0, Duration.ofMinutes(5), 0.2),
            environment,
            new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class));

    @Test
    void oneSummaryLineCoversTheRuntimeAndRedactsTheDatabase() {
        String summary = reporter.summaryLine();

        assertThat(summary)
                .contains("profile=default")
                .contains("project=demo-project")
                .contains("dataset=retail_demo")
                .contains("broker=in-memory")
                .contains("consumer=local")
                .contains("supplier=local")
                .contains("tracing=true")
                .contains("auth=api-key")
                .contains("adminKey=false")
                .doesNotContain("supersecret")
                .contains("password=***");
    }

    @Test
    void warningsNameTheExactMissingProductionConcerns() {
        assertThat(reporter.warnings())
                .anyMatch(warning -> warning.contains("not durable"))
                .anyMatch(warning -> warning.contains("OTLP endpoint points at localhost"))
                .anyMatch(warning -> warning.contains("ADMIN_API_KEY"));
    }

    @Test
    void redactionHandlesUrlAndCredentialForms() {
        assertThat(StartupReporter.redact("jdbc:postgresql://u:p@host/db")).isEqualTo(
                "jdbc:postgresql://***@host/db");
        assertThat(StartupReporter.redact(null)).isEqualTo("unset");
    }
}
