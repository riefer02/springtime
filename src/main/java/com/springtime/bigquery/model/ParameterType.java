package com.springtime.bigquery.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Closed set of supported query-parameter types. Legacy aliases are accepted on the wire and
 * normalised; unknown types fail at deserialization instead of deep in the binder.
 */
public enum ParameterType {

    BOOL,
    INT64,
    FLOAT64,
    NUMERIC,
    BIGNUMERIC,
    STRING,
    BYTES,
    TIMESTAMP,
    DATE,
    TIME,
    DATETIME,
    GEOGRAPHY,
    JSON,
    INTERVAL;

    @JsonCreator
    public static ParameterType fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Parameter type must not be blank");
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        normalised = switch (normalised) {
            case "INTEGER" -> "INT64";
            case "FLOAT" -> "FLOAT64";
            case "BOOLEAN" -> "BOOL";
            default -> normalised;
        };
        try {
            return valueOf(normalised);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported parameter type '%s'. Supported types: %s"
                            .formatted(raw, java.util.Arrays.toString(values())));
        }
    }

    @JsonValue
    public String wireName() {
        return name();
    }




}
