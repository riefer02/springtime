package com.springtime.bigquery.config;

import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.messaging.RetryProperties;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.springtime.bigquery.config.ConfigurationProblem.Severity.ERROR;
import static com.springtime.bigquery.config.ConfigurationProblem.Severity.WARNING;

/**
 * Pure cross-field configuration rules. No Spring dependencies, so the failure modes are unit
 * testable and the messages can name the environment variables operators actually set.
 */
public final class ConfigurationRules {

    public static final int MIN_API_KEY_LENGTH = 32;
    public static final int MAX_REQUEST_BYTES = 10_000_000;
    public static final int MAX_SQL_LENGTH = 1_024_000;
    public static final int MAX_PARAMETERS = 10_000;
    public static final int MAX_RESULTS = 100_000;
    public static final int MAX_CONCURRENT_QUERIES = 1_000;

    private ConfigurationRules() {
    }

    public static List<ConfigurationProblem> validate(ConfigurationInput input) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        BigQueryProperties bigQuery = input.bigQuery();
        AppHttpProperties http = input.http();
        AppPaginationProperties pagination = input.pagination();
        AppSecurityProperties security = input.security();
        RetailProperties retail = input.retail();
        MessagingProperties messaging = input.messaging();
        RetryProperties retry = input.retry();

        if (!StringUtils.hasText(bigQuery.projectId())) {
            problems.add(error("bigquery.project-id", "GOOGLE_CLOUD_PROJECT",
                    "is required; set it to the GCP project id"));
        }
        if (!StringUtils.hasText(bigQuery.location())) {
            problems.add(error("bigquery.location", "BIGQUERY_LOCATION",
                    "is required; use US, EU, or a region such as europe-west3"));
        }
        if (bigQuery.maxResults() > MAX_RESULTS) {
            problems.add(error("bigquery.max-results", "BIGQUERY_MAX_RESULTS",
                    "must be at most " + MAX_RESULTS));
        }
        if (bigQuery.maxConcurrentQueries() > MAX_CONCURRENT_QUERIES) {
            problems.add(error("bigquery.max-concurrent-queries", "BIGQUERY_MAX_CONCURRENT_QUERIES",
                    "must be at most " + MAX_CONCURRENT_QUERIES));
        }
        if (bigQuery.queryTimeout().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("bigquery.query-timeout", "BIGQUERY_QUERY_TIMEOUT",
                    "must be positive"));
        }
        if (bigQuery.acquireTimeout().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("bigquery.acquire-timeout", "BIGQUERY_ACQUIRE_TIMEOUT",
                    "must be positive"));
        }
        if (bigQuery.queryTimeout().compareTo(Duration.ofHours(6)) > 0) {
            problems.add(error("bigquery.query-timeout", "BIGQUERY_QUERY_TIMEOUT",
                    "must be at most 6h (BigQuery's query execution limit)"));
        }
        if (bigQuery.acquireTimeout().compareTo(bigQuery.queryTimeout()) >= 0) {
            problems.add(error("bigquery.acquire-timeout", "BIGQUERY_ACQUIRE_TIMEOUT",
                    "must be less than BIGQUERY_QUERY_TIMEOUT (" + bigQuery.queryTimeout() + ")"));
        }
        if (http.maxRequestBytes() < 1_024 || http.maxRequestBytes() > MAX_REQUEST_BYTES) {
            problems.add(error("app.http.max-request-bytes", "MAX_REQUEST_BYTES",
                    "must be between 1024 and " + MAX_REQUEST_BYTES));
        }
        if (http.maxSqlLength() > MAX_SQL_LENGTH) {
            problems.add(error("app.http.max-sql-length", "MAX_SQL_LENGTH",
                    "must be at most " + MAX_SQL_LENGTH));
        }
        if (http.maxSqlLength() >= http.maxRequestBytes()) {
            problems.add(error("app.http.max-sql-length", "MAX_SQL_LENGTH",
                    "must be less than MAX_REQUEST_BYTES (%d) so a maximum-length SQL body fits"
                            .formatted(http.maxRequestBytes())));
        }
        if (http.maxResponseBytes() > MAX_REQUEST_BYTES) {
            problems.add(error("app.http.max-response-bytes", "MAX_RESPONSE_BYTES",
                    "must be at most " + MAX_REQUEST_BYTES));
        }
        if (http.maxParameterValueLength() >= http.maxRequestBytes()) {
            problems.add(error("app.http.max-parameter-value-length", "MAX_PARAMETER_VALUE_LENGTH",
                    "must be less than MAX_REQUEST_BYTES (%d)".formatted(http.maxRequestBytes())));
        }
        if (http.maxParameters() > MAX_PARAMETERS) {
            problems.add(error("app.http.max-parameters", "MAX_PARAMETERS",
                    "must be at most " + MAX_PARAMETERS));
        }
        if (pagination.defaultPageSize() > pagination.maxPageSize()) {
            problems.add(error("app.pagination.default-page-size", "PAGE_DEFAULT_SIZE",
                    "must be <= PAGE_MAX_SIZE (" + pagination.maxPageSize() + ")"));
        }
        if (input.batch().size() > input.batch().maxPending()) {
            problems.add(error("retail.ledger.batch.size", "RETAIL_LEDGER_BATCH_MAX_PENDING",
                    "RETAIL_LEDGER_BATCH_MAX_PENDING must be >= RETAIL_LEDGER_BATCH_SIZE ("
                            + input.batch().size() + ")"));
        }
        if (input.batch().waitTimeout().compareTo(bigQuery.queryTimeout()) <= 0) {
            problems.add(error("retail.ledger.batch.wait-timeout", "RETAIL_LEDGER_BATCH_WAIT_TIMEOUT",
                    "must be greater than BIGQUERY_QUERY_TIMEOUT (" + bigQuery.queryTimeout() + ")"));
        }
        if (retail.defaultPageSize() > retail.maxPageSize()) {
            problems.add(error("retail.default-page-size", "RETAIL_DEFAULT_PAGE_SIZE",
                    "must be <= RETAIL_MAX_PAGE_SIZE (" + retail.maxPageSize() + ")"));
        }
        if (retail.maxBytesBilled() > bigQuery.maxBytesBilled()) {
            problems.add(error("retail.max-bytes-billed", "RETAIL_MAX_BYTES_BILLED",
                    "must be <= BIGQUERY_MAX_BYTES_BILLED (" + bigQuery.maxBytesBilled() + ")"));
        }
        if (retail.eventMaxFutureSkew().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("retail.event-max-future-skew", "RETAIL_EVENT_MAX_FUTURE_SKEW",
                    "must be positive"));
        }
        if (retail.eventMaxAge().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("retail.event-max-age", "RETAIL_EVENT_MAX_AGE",
                    "must be positive"));
        }
        if (retail.eventMaxFutureSkew().compareTo(retail.eventMaxAge()) >= 0) {
            problems.add(error("retail.event-max-future-skew", "RETAIL_EVENT_MAX_FUTURE_SKEW",
                    "must be less than RETAIL_EVENT_MAX_AGE (" + retail.eventMaxAge() + ")"));
        }

        if (Objects.equals(messaging.eventsTopic(), messaging.deadLetterTopic())) {
            problems.add(error("messaging.dead-letter-topic", "MESSAGING_DLQ_TOPIC",
                    "must differ from MESSAGING_EVENTS_TOPIC; a shared topic creates a poison loop"));
        }
        if (messaging.pubsubEnabled() && !StringUtils.hasText(messaging.subscription())) {
            problems.add(error("messaging.subscription", "MESSAGING_SUBSCRIPTION",
                    "is required when Pub/Sub is enabled"));
        }
        if (messaging.localConsumerPollIntervalMs() < 50) {
            problems.add(error("messaging.local-consumer-poll-interval-ms",
                    "MESSAGING_LOCAL_CONSUMER_POLL_INTERVAL_MS", "must be at least 50ms"));
        }
        if (!messaging.pubsubEnabled()) {
            problems.add(warning("messaging.pubsub-enabled", "MESSAGING_PUBSUB_ENABLED",
                    "the in-memory broker is local-only and not durable; set MESSAGING_PUBSUB_ENABLED=true "
                            + "in any shared environment"));
        }

        if (retry.initialDelay().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("messaging.retry.initial-delay", "MESSAGING_RETRY_INITIAL_DELAY",
                    "must be positive"));
        }
        if (retry.maxDelay().compareTo(Duration.ZERO) <= 0) {
            problems.add(error("messaging.retry.max-delay", "MESSAGING_RETRY_MAX_DELAY",
                    "must be positive"));
        }
        if (retry.maxDelay().compareTo(retry.initialDelay()) < 0) {
            problems.add(error("messaging.retry.max-delay", "MESSAGING_RETRY_MAX_DELAY",
                    "must be >= MESSAGING_RETRY_INITIAL_DELAY (" + retry.initialDelay() + ")"));
        }

        List<String> apiKeys = security.apiKeys();
        for (String apiKey : apiKeys) {
            validateApiKeyValue(apiKey, "app.security.api-key", "API_KEY", problems);
        }
        for (String adminKey : security.adminApiKeys()) {
            validateApiKeyValue(adminKey, "app.security.admin-api-key", "ADMIN_API_KEY", problems);
        }
        if (apiKeys.size() > 1) {
            problems.add(warning("app.security.api-key-previous", "API_KEY_PREVIOUS",
                    "a previous API key is still valid; remove it once rotation is complete"));
        }

        boolean oidcEnabled = StringUtils.hasText(input.oidcIssuerUri())
                || StringUtils.hasText(input.oidcJwkSetUri());
        if (!security.apiKeyEnabled() && !oidcEnabled) {
            problems.add(error("app.security.api-key", "API_KEY",
                    "no authentication is configured; set API_KEY to a random value of at least 32 characters "
                            + "(run \"make key\"), or set SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI "
                            + "and SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES"));
        }

        if (oidcEnabled) {
            if (StringUtils.hasText(input.oidcIssuerUri())) {
                validateOidcIssuer(input.oidcIssuerUri(), security.allowInsecureOidc(), problems);
            }
            if (StringUtils.hasText(input.oidcJwkSetUri())) {
                validateJwkSetUri(input.oidcJwkSetUri(), security.allowInsecureOidc(), problems);
            }
            if (!security.oidcAllowedDomains().isEmpty()) {
                if (!StringUtils.hasText(input.oidcIssuerUri())) {
                    problems.add(error("app.security.oidc-allowed-domains", "OIDC_ALLOWED_DOMAINS",
                            "requires SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI so the "
                                    + "Google issuer can be verified"));
                } else if (!isGoogleIssuer(input.oidcIssuerUri())) {
                    problems.add(error("app.security.oidc-allowed-domains", "OIDC_ALLOWED_DOMAINS",
                            "hosted-domain (hd) mapping is Google-specific; use a Google issuer or "
                                    + "remove the allow-list and map a roles claim instead"));
                }
            }
            if (!StringUtils.hasText(input.oidcAudiences())) {
                problems.add(error("spring.security.oauth2.resourceserver.jwt.audiences",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES",
                        "is required when OIDC is enabled; tokens issued for other APIs must be rejected"));
            }
        }

        if (!bigQuery.readOnly()) {
            problems.add(warning("bigquery.read-only", "BIGQUERY_READ_ONLY",
                    "read-only mode is OFF; DML/DDL is allowed, so IAM must be the boundary"));
        }
        if (!apiKeys.isEmpty() && oidcEnabled) {
            problems.add(warning("app.security.api-key", "API_KEY",
                    "API key and OIDC are both enabled; prefer OIDC for shared deployments"));
        }
        return problems;
    }

    /** A read key and an admin key must be distinct; production API-key auth needs both. */
    public static List<ConfigurationProblem> validateApiKeySplit(
            AppSecurityProperties security, boolean productionProfile) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (StringUtils.hasText(security.adminApiKey())
                && security.apiKeys().contains(security.adminApiKey())) {
            problems.add(error("app.security.admin-api-key", "ADMIN_API_KEY",
                    "must differ from API_KEY; a shared value defeats the read/admin split"));
        }
        if (productionProfile && security.apiKeyEnabled() && security.allowApiKeyInProduction()
                && security.adminApiKeys().isEmpty()) {
            problems.add(error("app.security.admin-api-key", "ADMIN_API_KEY",
                    "is required when API-key authentication is enabled in production"));
        }
        return problems;
    }

    /** The in-memory broker is a local demo, not a durable production broker. */
    public static List<ConfigurationProblem> validateProductionBroker(
            MessagingProperties messaging, boolean productionProfile) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (productionProfile && !messaging.pubsubEnabled()) {
            problems.add(error("messaging.pubsub-enabled", "MESSAGING_PUBSUB_ENABLED",
                    "must be true in production; the in-memory broker is not durable"));
        }
        return problems;
    }

    /** The demo dataset must not be the production default. */
    public static List<ConfigurationProblem> validateProductionDataset(
            RetailProperties retail, boolean productionProfile) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (productionProfile && retail.dataset().endsWith("_demo")) {
            problems.add(error("retail.dataset", "RETAIL_DATASET",
                    "must not point at a demo dataset in production"));
        }
        return problems;
    }

    /** A supplier adapter must be selected explicitly; a demo must not be the silent default. */
    public static List<ConfigurationProblem> validateSupplierGateway(String gateway) {
        if (!StringUtils.hasText(gateway)) {
            return List.of(error("control.supplier.gateway", "SUPPLIER_GATEWAY",
                    "is required; set 'local' for a demo or a real adapter"));
        }
        return List.of();
    }

    /**
     * The API key is a local operator credential, not a production identity. A production profile
     * must use OIDC unless the operator explicitly opts in.
     */
    public static List<ConfigurationProblem> validateProductionApiKey(
            AppSecurityProperties security, boolean productionProfile) {
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (productionProfile && security.apiKeyEnabled() && !security.allowApiKeyInProduction()) {
            problems.add(error("app.security.api-key", "API_KEY",
                    "API key is a local operator credential; use OIDC or set "
                            + "ALLOW_API_KEY_IN_PRODUCTION=true"));
        }
        return problems;
    }

    private static void validateApiKeyValue(String apiKey, String property, String env,
                                            List<ConfigurationProblem> problems) {
        if (!apiKey.equals(apiKey.strip())) {
            problems.add(error(property, env,
                    "has leading or trailing whitespace; remove it (the value is not logged)"));
        }
        if (apiKey.length() < MIN_API_KEY_LENGTH) {
            problems.add(error(property, env,
                    "is only %d characters; use at least %d (run \"make key\"; the value is not logged)"
                            .formatted(apiKey.length(), MIN_API_KEY_LENGTH)));
        }
    }

    private static boolean isGoogleIssuer(String issuerUri) {
        if (!StringUtils.hasText(issuerUri)) {
            return false;
        }
        try {
            String host = new URI(issuerUri).getHost();
            return "accounts.google.com".equals(host) || "securetoken.google.com".equals(host);
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static void validateJwkSetUri(String jwkSetUri, boolean allowInsecureOidc,
                                          List<ConfigurationProblem> problems) {
        try {
            URI uri = new URI(jwkSetUri);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!https && !allowInsecureOidc) {
                problems.add(error("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI",
                        "must use https; set ALLOW_INSECURE_OIDC=true only for local development"));
            }
            if (!uri.isAbsolute() || uri.getHost() == null) {
                problems.add(error("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI",
                        "must be an absolute URL such as https://issuer.example.com/jwks"));
            }
        } catch (URISyntaxException exception) {
            problems.add(error("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                    "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI",
                    "is not a valid URI"));
        }
    }

    private static void validateOidcIssuer(String issuerUri, boolean allowInsecureOidc,
                                           List<ConfigurationProblem> problems) {
        try {
            URI uri = new URI(issuerUri);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!https && !allowInsecureOidc) {
                problems.add(error("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
                        "must use https; set ALLOW_INSECURE_OIDC=true only for local development"));
            }
            if (!uri.isAbsolute() || uri.getHost() == null) {
                problems.add(error("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
                        "must be an absolute URL such as https://tenant.example.com/"));
            }
        } catch (URISyntaxException exception) {
            problems.add(error("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                    "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
                    "is not a valid URI"));
        }
    }

    private static ConfigurationProblem error(String property, String env, String message) {
        return new ConfigurationProblem(property, env, message, ERROR);
    }

    private static ConfigurationProblem warning(String property, String env, String message) {
        return new ConfigurationProblem(property, env, message, WARNING);
    }
}
