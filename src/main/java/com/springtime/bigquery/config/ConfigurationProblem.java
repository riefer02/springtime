package com.springtime.bigquery.config;

/** One actionable startup configuration problem. */
public record ConfigurationProblem(String property, String environmentVariable, String message, Severity severity) {

    public String format() {
        return "- %s (%s): %s".formatted(environmentVariable, property, message);
    }

    public String logLine() {
        return "STARTUP_CONFIG_%s property=%s env=%s message=%s"
                .formatted(severity, property, environmentVariable, message);
    }

    public enum Severity {
        ERROR,
        WARNING
    }
}
