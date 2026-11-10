package com.springtime.bigquery.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.messaging.RetryProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs cross-field validation during context refresh, before the servlet container starts
 * accepting traffic. Aggregates all problems so operators can fix them in one restart.
 */
@Component
public class StartupConfigurationValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigurationValidator.class);

    private final BigQueryProperties bigQuery;
    private final AppHttpProperties http;
    private final AppPaginationProperties pagination;
    private final AppSecurityProperties security;
    private final RetailProperties retail;
    private final MessagingProperties messaging;
    private final RetryProperties retry;
    private final String oidcIssuerUri;
    private final String oidcAudiences;
    private final String oidcJwkSetUri;
    private final String supplierGateway;
    private final InventoryBatchProperties batch;
    private final Environment environment;

    public StartupConfigurationValidator(
            BigQueryProperties bigQuery,
            AppHttpProperties http,
            AppPaginationProperties pagination,
            AppSecurityProperties security,
            RetailProperties retail,
            MessagingProperties messaging,
            RetryProperties retry,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String oidcIssuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.audiences:}") String oidcAudiences,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String oidcJwkSetUri,
            @Value("${control.supplier.gateway:}") String supplierGateway,
            InventoryBatchProperties batch,
            Environment environment) {
        this.bigQuery = bigQuery;
        this.http = http;
        this.pagination = pagination;
        this.security = security;
        this.retail = retail;
        this.messaging = messaging;
        this.retry = retry;
        this.oidcIssuerUri = oidcIssuerUri;
        this.oidcAudiences = oidcAudiences;
        this.oidcJwkSetUri = oidcJwkSetUri;
        this.supplierGateway = supplierGateway;
        this.batch = batch;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<ConfigurationProblem> problems = new ArrayList<>(ConfigurationRules.validate(
                new ConfigurationInput(bigQuery, http, pagination, security, retail, messaging, retry,
                        oidcIssuerUri, oidcAudiences, oidcJwkSetUri, batch)));
        boolean production = environment.matchesProfiles("prod", "production");
        problems.addAll(ConfigurationRules.validateProductionApiKey(security, production));
        problems.addAll(ConfigurationRules.validateApiKeySplit(security, production));
        problems.addAll(ConfigurationRules.validateProductionBroker(messaging, production));
        problems.addAll(ConfigurationRules.validateProductionDataset(retail, production));
        problems.addAll(ConfigurationRules.validateSupplierGateway(supplierGateway));

        problems.forEach(problem -> {
            if (problem.severity() == ConfigurationProblem.Severity.ERROR) {
                log.error(problem.logLine());
            } else {
                log.warn(problem.logLine());
            }
        });

        List<ConfigurationProblem> errors = problems.stream()
                .filter(problem -> problem.severity() == ConfigurationProblem.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new ConfigurationValidationException(errors);
        }
    }
}
