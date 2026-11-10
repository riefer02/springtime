package com.springtime.bigquery.service;

import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.error.InvalidQueryException;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates and binds named query parameters to BigQuery's typed representation.
 *
 * <p>Values arrive as JSON scalars. NUMERIC/BIGNUMERIC should be sent as strings to avoid a
 * floating-point round trip; BYTES must be base64; JSON must be a JSON-encoded string. Objects and
 * arrays are rejected for scalar types.</p>
 */
final class QueryParameterBinder {

    private QueryParameterBinder() {
    }

    static Map<String, QueryParameterValue> bind(List<QueryParameter> parameters, int maxValueLength) {
        Map<String, QueryParameterValue> named = new LinkedHashMap<>();
        for (QueryParameter parameter : parameters) {
            if (named.containsKey(parameter.name())) {
                throw new InvalidQueryException("Duplicate query parameter: " + parameter.name());
            }
            named.put(parameter.name(), toValue(parameter, maxValueLength));
        }
        return named;
    }

    private static QueryParameterValue toValue(QueryParameter parameter, int maxValueLength) {
        ParameterType type = parameter.type();
        Object raw = parameter.value();
        if (raw instanceof Map || raw instanceof Collection) {
            throw new InvalidQueryException("Parameter '" + parameter.name()
                    + "' must be a JSON scalar; send objects/arrays as strings");
        }
        if (raw != null && String.valueOf(raw).length() > maxValueLength) {
            throw new InvalidQueryException("Parameter '" + parameter.name()
                    + "' exceeds the maximum length of " + maxValueLength + " characters");
        }

        String value = switch (type) {
            case BOOL -> toBoolean(parameter.name(), raw);
            case INT64 -> toInt64(parameter.name(), raw);
            case FLOAT64 -> toFloat64(parameter.name(), raw);
            case NUMERIC, BIGNUMERIC -> toDecimal(parameter.name(), raw);
            case BYTES -> toBase64(parameter.name(), raw);
            case STRING, TIMESTAMP, DATE, TIME, DATETIME, GEOGRAPHY, JSON, INTERVAL ->
                    raw == null ? null : String.valueOf(raw);
        };

        return QueryParameterValue.newBuilder()
                .setType(StandardSQLTypeName.valueOf(type.name()))
                .setValue(value)
                .build();
    }

    private static String toBoolean(String name, Object raw) {
        if (raw instanceof Boolean bool) {
            return bool.toString();
        }
        String text = String.valueOf(raw);
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw new InvalidQueryException("Parameter '" + name + "' must be true or false");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    private static String toInt64(String name, Object raw) {
        try {
            return Long.toString(Long.parseLong(String.valueOf(raw)));
        } catch (NumberFormatException exception) {
            throw new InvalidQueryException("Parameter '" + name + "' must be a valid INT64");
        }
    }

    private static String toFloat64(String name, Object raw) {
        String text = String.valueOf(raw);
        try {
            Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            throw new InvalidQueryException("Parameter '" + name + "' must be a valid FLOAT64");
        }
        return text;
    }

    private static String toDecimal(String name, Object raw) {
        if (!(raw instanceof String text)) {
            throw new InvalidQueryException("Parameter '" + name
                    + "' must be a string to preserve NUMERIC/BIGNUMERIC precision");
        }
        try {
            return new BigDecimal(text).toPlainString();
        } catch (NumberFormatException exception) {
            throw new InvalidQueryException("Parameter '" + name + "' must be a valid decimal number");
        }
    }

    private static String toBase64(String name, Object raw) {
        String text = String.valueOf(raw);
        try {
            Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException exception) {
            throw new InvalidQueryException("Parameter '" + name + "' must be base64-encoded BYTES");
        }
        return text;
    }


}
