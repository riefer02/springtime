package com.springtime.bigquery.config;

import java.util.List;

/** Aggregated, actionable startup configuration failure. */
public class ConfigurationValidationException extends RuntimeException {

    private final List<ConfigurationProblem> problems;

    public ConfigurationValidationException(List<ConfigurationProblem> problems) {
        super("Invalid startup configuration: " + problems.size() + " problem(s)");
        this.problems = List.copyOf(problems);
    }

    public List<ConfigurationProblem> problems() {
        return problems;
    }
}
