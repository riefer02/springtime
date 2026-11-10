package com.springtime.bigquery.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConfigurationValidationFailureAnalyzer extends AbstractFailureAnalyzer<ConfigurationValidationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationValidationException cause) {
        String description = "STARTUP_CONFIG_INVALID: springtime has %d configuration problem(s)."
                .formatted(cause.problems().size());
        String action = "Fix these settings and restart:\n"
                + cause.problems().stream()
                .map(ConfigurationProblem::format)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new FailureAnalysis(description, action, cause);
    }
}
