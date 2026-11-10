package com.springtime.bigquery.config;

import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.messaging.RetryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationRulesTest {

    private static final String VALID_KEY = "k".repeat(32);

    @Test
    void acceptsAValidDefaultConfiguration() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                VALID_KEY, "https://issuer.example.com/", "springtime-api", "", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).isEmpty();
    }

    @Test
    void rejectsMissingAuthentication() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "", "", "", "", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).extracting(ConfigurationProblem::environmentVariable)
                .contains("API_KEY");
    }

    @Test
    void rejectsShortKeysWithoutEchoingTheValue() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "short-key", "", "", "", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).anySatisfy(problem -> {
            assertThat(problem.environmentVariable()).isEqualTo("API_KEY");
            assertThat(problem.message()).contains("at least 32").doesNotContain("short-key");
        });
    }

    @Test
    void rejectsInvalidCrossFieldLimits() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                VALID_KEY, "", "", "", true,
                2_000, 1_000, 262_144, 262_144, Duration.ofSeconds(60), Duration.ofSeconds(30)));

        assertThat(errors(problems)).extracting(ConfigurationProblem::environmentVariable)
                .contains("PAGE_DEFAULT_SIZE", "MAX_SQL_LENGTH", "BIGQUERY_ACQUIRE_TIMEOUT");
    }

    @Test
    void rejectsZeroDurations() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                VALID_KEY, "", "", "", true,
                100, 1_000, 100_000, 262_144, Duration.ZERO, Duration.ZERO));

        assertThat(errors(problems)).extracting(ConfigurationProblem::environmentVariable)
                .contains("BIGQUERY_ACQUIRE_TIMEOUT", "BIGQUERY_QUERY_TIMEOUT");
    }

    @Test
    void validatesOidcIssuerAndAudience() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "", "http://issuer.example.com/", "", "https://jwks.example.com/", false,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).extracting(ConfigurationProblem::environmentVariable)
                .contains(
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI",
                        "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES");
    }

    @Test
    void allowsIssuerAndJwkSetTogether() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "", "https://issuer.example.com/", "springtime-api",
                "https://issuer.example.com/jwks", false,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).isEmpty();
    }

    @Test
    void emitsWarningsForUnsafeButSupportedModes() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                VALID_KEY, "https://issuer.example.com/", "springtime-api", "", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(problems).anySatisfy(problem -> {
            assertThat(problem.severity()).isEqualTo(ConfigurationProblem.Severity.WARNING);
            assertThat(problem.environmentVariable()).isEqualTo("API_KEY");
        });
    }

    @Test
    void rejectsSharedEventsAndDeadLetterTopic() {
        MessagingProperties messaging = new MessagingProperties(
                false, "retail-events", "retail-events",
                "retail-events-worker", true, true, 500, 1_000);

        assertThat(errors(ConfigurationRules.validate(inputWith(messaging, retry()))))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("MESSAGING_DLQ_TOPIC");
    }

    @Test
    void rejectsRetryCeilingBelowInitialDelay() {
        RetryProperties retry = new RetryProperties(
                5, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(1), 0.2);

        assertThat(errors(ConfigurationRules.validate(inputWith(messaging(), retry))))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("MESSAGING_RETRY_MAX_DELAY");
    }

    @Test
    void rejectsInvalidEventWindow() {
        RetailProperties retail = new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 1_024L,
                "USD", Duration.ZERO, Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
        ConfigurationInput input = new ConfigurationInput(
                bigQuery(true), http(), pagination(), security(VALID_KEY), retail,
                messaging(), retry(), "", "", "");

        assertThat(errors(ConfigurationRules.validate(input)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("RETAIL_EVENT_MAX_FUTURE_SKEW");
    }

    @Test
    void rejectsHostedDomainMappingWithoutAnIssuer() {
        ConfigurationInput base = input(VALID_KEY, "", "springtime-api",
                "https://issuer.example.com/jwks", true, 100, 1_000, 100_000, 262_144,
                Duration.ofSeconds(2), Duration.ofSeconds(60));
        ConfigurationInput withDomains = new ConfigurationInput(
                base.bigQuery(), base.http(), base.pagination(),
                new AppSecurityProperties(VALID_KEY, "", false, "roles",
                        List.of("example.com"), List.of(), false, ""),
                base.retail(), base.messaging(), base.retry(),
                base.oidcIssuerUri(), base.oidcAudiences(), base.oidcJwkSetUri());

        assertThat(errors(ConfigurationRules.validate(withDomains)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("OIDC_ALLOWED_DOMAINS");
    }

    @Test
    void rejectsHostedDomainMappingForANonGoogleIssuer() {
        ConfigurationInput base = input(VALID_KEY, "https://keycloak.example.com/realms/retail",
                "springtime-api", "", true, 100, 1_000, 100_000, 262_144,
                Duration.ofSeconds(2), Duration.ofSeconds(60));
        ConfigurationInput withDomains = new ConfigurationInput(
                base.bigQuery(), base.http(), base.pagination(),
                new AppSecurityProperties(VALID_KEY, "", false, "roles",
                        List.of("example.com"), List.of(), false, ""),
                base.retail(), base.messaging(), base.retry(),
                base.oidcIssuerUri(), base.oidcAudiences(), base.oidcJwkSetUri());

        assertThat(errors(ConfigurationRules.validate(withDomains)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("OIDC_ALLOWED_DOMAINS");
    }

    @Test
    void acceptsHostedDomainMappingForGoogle() {
        ConfigurationInput base = input(VALID_KEY, "https://accounts.google.com",
                "springtime-api", "", true, 100, 1_000, 100_000, 262_144,
                Duration.ofSeconds(2), Duration.ofSeconds(60));
        ConfigurationInput withDomains = new ConfigurationInput(
                base.bigQuery(), base.http(), base.pagination(),
                new AppSecurityProperties(VALID_KEY, "", false, "roles",
                        List.of("example.com"), List.of(), false, ""),
                base.retail(), base.messaging(), base.retry(),
                base.oidcIssuerUri(), base.oidcAudiences(), base.oidcJwkSetUri());

        assertThat(errors(ConfigurationRules.validate(withDomains)))
                .extracting(ConfigurationProblem::environmentVariable)
                .doesNotContain("OIDC_ALLOWED_DOMAINS");
    }

    @Test
    void rejectsASharedReadAndAdminKey() {
        AppSecurityProperties security = new AppSecurityProperties(
                VALID_KEY, "", false, "roles", List.of(), List.of(), false, VALID_KEY);

        assertThat(errors(ConfigurationRules.validateApiKeySplit(security, false)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("ADMIN_API_KEY");
    }

    @Test
    void requiresASeparateAdminKeyWhenProductionApiKeyAuthIsAllowed() {
        AppSecurityProperties security = new AppSecurityProperties(
                VALID_KEY, "", false, "roles", List.of(), List.of(), true, "");

        assertThat(errors(ConfigurationRules.validateApiKeySplit(security, true)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("ADMIN_API_KEY");

        AppSecurityProperties split = new AppSecurityProperties(
                VALID_KEY, "", false, "roles", List.of(), List.of(), true,
                "admin-0123456789abcdef0123456789");
        assertThat(errors(ConfigurationRules.validateApiKeySplit(split, true))).isEmpty();
    }

    @Test
    void rejectsTheInMemoryBrokerInProduction() {
        MessagingProperties messaging = new MessagingProperties(
                false, "retail-events", "retail-events-dlq",
                "retail-events-worker", true, true, 500, 1_000);

        assertThat(errors(ConfigurationRules.validateProductionBroker(messaging, true)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("MESSAGING_PUBSUB_ENABLED");
        assertThat(errors(ConfigurationRules.validateProductionBroker(messaging, false))).isEmpty();
    }

    @Test
    void rejectsTheDemoDatasetInProduction() {
        assertThat(errors(ConfigurationRules.validateProductionDataset(retail(), true)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("RETAIL_DATASET");
        assertThat(errors(ConfigurationRules.validateProductionDataset(retail(), false))).isEmpty();
    }

    @Test
    void requiresAnExplicitSupplierGateway() {
        assertThat(errors(ConfigurationRules.validateSupplierGateway("")))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("SUPPLIER_GATEWAY");
        assertThat(errors(ConfigurationRules.validateSupplierGateway("local"))).isEmpty();
    }

    @Test
    void rejectsABatchQueueSmallerThanTheBatchSize() {
        ConfigurationInput base = new ConfigurationInput(
                bigQuery(true), new AppHttpProperties(262_144, 100_000, 65_536, 100, 5_000_000, true),
                pagination(), security(VALID_KEY), retail(), messaging(), retry(), "", "", "");
        ConfigurationInput input = new ConfigurationInput(
                base.bigQuery(), base.http(), base.pagination(), base.security(), base.retail(),
                base.messaging(), base.retry(), "", "", "",
                new com.springtime.bigquery.config.InventoryBatchProperties(
                        true, 500, 25, 100, 33_554_432, java.time.Duration.ofSeconds(75)));

        assertThat(errors(ConfigurationRules.validate(input)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("RETAIL_LEDGER_BATCH_MAX_PENDING");
    }

    @Test
    void rejectsAnUnboundedResponseBudget() {
        ConfigurationInput input = new ConfigurationInput(
                bigQuery(true), new AppHttpProperties(262_144, 100_000, 65_536, 100,
                        20_000_000, true),
                pagination(), security(VALID_KEY), retail(), messaging(), retry(), "", "", "");

        assertThat(errors(ConfigurationRules.validate(input)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("MAX_RESPONSE_BYTES");
    }

    @Test
    void rejectsApiKeyInProductionWithoutAnExplicitOptIn() {
        AppSecurityProperties security = new AppSecurityProperties(
                VALID_KEY, "", false, "roles", List.of(), List.of(), false, "");

        assertThat(errors(ConfigurationRules.validateProductionApiKey(security, true)))
                .extracting(ConfigurationProblem::environmentVariable)
                .contains("API_KEY");
    }

    @Test
    void allowsApiKeyInProductionWithAnExplicitOptIn() {
        AppSecurityProperties security = new AppSecurityProperties(
                VALID_KEY, "", false, "roles", List.of(), List.of(), true, "");

        assertThat(ConfigurationRules.validateProductionApiKey(security, true)).isEmpty();
    }

    @Test
    void allowsJwkSetWithoutAnIssuer() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "", "", "springtime-api", "https://issuer.example.com/jwks", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).isEmpty();
    }

    @Test
    void rejectsNonHttpsJwkSet() {
        List<ConfigurationProblem> problems = ConfigurationRules.validate(input(
                "", "", "springtime-api", "http://issuer.example.com/jwks", true,
                100, 1_000, 100_000, 262_144, Duration.ofSeconds(2), Duration.ofSeconds(60)));

        assertThat(errors(problems)).extracting(ConfigurationProblem::environmentVariable)
                .contains("SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI");
    }

    private static List<ConfigurationProblem> errors(List<ConfigurationProblem> problems) {
        return problems.stream()
                .filter(problem -> problem.severity() == ConfigurationProblem.Severity.ERROR)
                .toList();
    }

    private static ConfigurationInput inputWith(MessagingProperties messaging, RetryProperties retry) {
        return new ConfigurationInput(
                bigQuery(true), http(), pagination(), security(VALID_KEY), retail(),
                messaging, retry, "", "", "");
    }

    private static ConfigurationInput input(
            String apiKey,
            String issuer,
            String audience,
            String jwkSet,
            boolean readOnly,
            int pageDefault,
            int pageMax,
            int maxSql,
            long maxRequest,
            Duration acquire,
            Duration query) {
        BigQueryProperties bigQuery = new BigQueryProperties(
                "test-project", "US", "", 1_024, 1_000, true, readOnly,
                8, acquire, query);
        AppHttpProperties http = new AppHttpProperties(maxRequest, maxSql, 65_536, 100, 5_000_000, true);
        AppPaginationProperties pagination = new AppPaginationProperties(pageDefault, pageMax);
        AppSecurityProperties security = new AppSecurityProperties(
                apiKey, "", false, "roles", List.of(), List.of(), false, "");
        return new ConfigurationInput(bigQuery, http, pagination, security, retail(),
                messaging(), retry(), issuer, audience, jwkSet);
    }

    private static BigQueryProperties bigQuery(boolean readOnly) {
        return new BigQueryProperties("test-project", "US", "", 1_024, 1_000, true, readOnly,
                8, Duration.ofSeconds(2), Duration.ofSeconds(60));
    }

    private static AppHttpProperties http() {
        return new AppHttpProperties(262_144, 100_000, 65_536, 100, 5_000_000, true);
    }

    private static AppPaginationProperties pagination() {
        return new AppPaginationProperties(100, 1_000);
    }

    private static AppSecurityProperties security(String apiKey) {
        return new AppSecurityProperties(apiKey, "", false, "roles", List.of(), List.of(), false, "");
    }

    private static RetailProperties retail() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 1_024L,
                "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    }

    private static MessagingProperties messaging() {
        return new MessagingProperties(
                false, "retail-events", "retail-events-dlq",
                "retail-events-worker", true, true, 500, 1_000);
    }

    private static RetryProperties retry() {
        return new RetryProperties(5, Duration.ofMillis(200), 2.0, Duration.ofSeconds(10), 0.2);
    }
}
