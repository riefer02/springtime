package com.springtime.bigquery.config;

import com.springtime.bigquery.control.supplier.SupplierOutboxProperties;
import com.springtime.bigquery.messaging.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One boot summary and the warnings an operator needs before the first request. Everything here is
 * configuration-derived and secret-free: the datasource is redacted, API keys are never logged.
 */
@Component
class StartupReporter {

    private static final Logger log = LoggerFactory.getLogger(StartupReporter.class);

    private final BigQueryProperties bigQuery;
    private final RetailProperties retail;
    private final MessagingProperties messaging;
    private final AppSecurityProperties security;
    private final SupplierOutboxProperties outbox;
    private final Environment environment;
    private final ObjectProvider<BuildProperties> buildProperties;

    StartupReporter(BigQueryProperties bigQuery,
                    RetailProperties retail,
                    MessagingProperties messaging,
                    AppSecurityProperties security,
                    SupplierOutboxProperties outbox,
                    Environment environment,
                    ObjectProvider<BuildProperties> buildProperties) {
        this.bigQuery = bigQuery;
        this.retail = retail;
        this.messaging = messaging;
        this.security = security;
        this.outbox = outbox;
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        log.info(summaryLine());
        warnings().forEach(log::warn);
        if (StringUtils.hasText(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))) {
            log.info("Auth: OIDC issuer configured; API key {}",
                    security.apiKeyEnabled() ? "also enabled" : "disabled");
        }
    }

    String summaryLine() {
        BuildProperties build = buildProperties.getIfAvailable();
        return "springtime ready: profile=%s port=%s version=%s project=%s location=%s dataset=%s "
                .formatted(profile(), port(), build == null ? "dev" : build.getVersion(),
                        bigQuery.projectId(), bigQuery.location(), retail.dataset())
                + "database=%s flyway=%s broker=%s consumer=%s supplier=%s projection=%s "
                .formatted(redact(environment.getProperty("spring.datasource.url")),
                        environment.getProperty("spring.flyway.enabled", "false"),
                        messaging.pubsubEnabled() ? "pubsub:" + messaging.eventsTopic() : "in-memory",
                        messaging.pubsubEnabled()
                                ? "pull:" + messaging.subscription() + (messaging.consumerEnabled() ? "" : " (disabled)")
                                : "local" + (messaging.localConsumerEnabled() ? "" : " (disabled)"),
                        supplierGateway(),
                        environment.getProperty("retail.projection.enabled", "false"))
                + "tracing=%s otlp=%s docs=%s auth=%s adminKey=%s"
                .formatted(tracingEnabled(), otlpEndpoint(),
                        environment.getProperty("springdoc.api-docs.enabled", "false"),
                        authMode(), !security.adminApiKeys().isEmpty());
    }

    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (!messaging.pubsubEnabled()) {
            warnings.add("broker=in-memory is not durable; enable MESSAGING_PUBSUB_ENABLED=true "
                    + "with Pub/Sub for shared or production deployments");
        }
        if (tracingEnabled() && isLocal(otlpEndpoint())) {
            warnings.add("OTLP endpoint points at localhost; spans are dropped unless a collector "
                    + "is running on this host (start `make obs-up` or point at Grafana Cloud)");
        }
        if (security.apiKeyEnabled() && security.adminApiKeys().isEmpty()) {
            warnings.add("single API key grants operator authority; set ADMIN_API_KEY to split "
                    + "read-only and operator keys");
        }
        if (!security.apiKeyEnabled()
                && !StringUtils.hasText(environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))) {
            warnings.add("no API key or OIDC issuer is configured; startup validation should have failed");
        }
        if (!outbox.enabled()) {
            warnings.add("supplier outbox is disabled; purchase-order submission will not enqueue transmissions");
        } else if (!outbox.workerEnabled()) {
            warnings.add("supplier outbox worker is disabled; transmissions will stay PENDING");
        }
        if (Boolean.parseBoolean(environment.getProperty("spring.flyway.enabled", "false"))) {
            warnings.add("Flyway runs during startup (local/demo); production applies migrations as a separate job");
        }
        if (Boolean.parseBoolean(environment.getProperty("springdoc.api-docs.enabled", "false"))) {
            warnings.add("springdoc is enabled; /v3/api-docs and Swagger UI are public while enabled");
        }
        return warnings;
    }

    static String redact(String url) {
        if (!StringUtils.hasText(url)) {
            return "unset";
        }
        return url.replaceAll("(?i)(password|user)=([^&;]*)", "$1=***")
                .replaceAll("://[^/@]+@", "://***@");
    }

    private String profile() {
        String[] active = environment.getActiveProfiles();
        return active.length == 0 ? "default" : String.join(",", active);
    }

    private String port() {
        return environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
    }

    private boolean tracingEnabled() {
        return Boolean.parseBoolean(environment.getProperty("management.tracing.enabled", "true"));
    }

    private String otlpEndpoint() {
        String configured = environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint");
        return StringUtils.hasText(configured) ? configured
                : environment.getProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4318/v1/traces");
    }

    private String supplierGateway() {
        String gateway = environment.getProperty("control.supplier.gateway", "");
        return StringUtils.hasText(gateway) ? gateway : "unset";
    }

    private String authMode() {
        boolean apiKey = security.apiKeyEnabled();
        boolean oidc = StringUtils.hasText(
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        if (apiKey && oidc) {
            return "api-key+oidc";
        }
        if (apiKey) {
            return "api-key";
        }
        return oidc ? "oidc" : "none";
    }

    private static boolean isLocal(String endpoint) {
        String value = endpoint == null ? "" : endpoint.toLowerCase(Locale.ROOT);
        return value.contains("localhost") || value.contains("127.0.0.1") || value.contains("[::1]");
    }
}
