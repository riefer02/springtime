package com.springtime.bigquery.service;

import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.error.InvalidQueryException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParameterBinderTest {

    private static final int MAX_VALUE_LENGTH = 65_536;

    @Test
    void bindsAndNormalisesValues() {
        Map<String, QueryParameterValue> bound = QueryParameterBinder.bind(List.of(
                new QueryParameter("count", ParameterType.INT64, "42"),
                new QueryParameter("ratio", ParameterType.FLOAT64, "1.5"),
                new QueryParameter("enabled", ParameterType.BOOL, "TRUE"),
                new QueryParameter("amount", ParameterType.NUMERIC, "12345678901234567890.1234"),
                new QueryParameter("legacy", ParameterType.INT64, 7),
                new QueryParameter("nothing", ParameterType.STRING, null)), MAX_VALUE_LENGTH);

        assertThat(bound.get("count").getType()).isEqualTo(StandardSQLTypeName.INT64);
        assertThat(bound.get("count").getValue()).isEqualTo("42");
        assertThat(bound.get("ratio").getValue()).isEqualTo("1.5");
        assertThat(bound.get("enabled").getValue()).isEqualTo("true");
        assertThat(bound.get("amount").getValue()).isEqualTo("12345678901234567890.1234");
        assertThat(bound.get("legacy").getType()).isEqualTo(StandardSQLTypeName.INT64);
        assertThat(bound.get("nothing").getValue()).isNull();
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.STRING, "a"),
                new QueryParameter("x", ParameterType.STRING, "b")), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsObjectsAndArraysForScalarTypes() {
        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.STRING, Map.of("a", 1))), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class);

        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.STRING, List.of(1, 2))), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void rejectsUnsupportedTypesAndMalformedValues() {
        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.NUMERIC, "not-a-number")), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class);

        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.NUMERIC, 1.5d)), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("must be a string");

        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.INT64, "not-a-number")), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class);

        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.BYTES, "not base64!")), MAX_VALUE_LENGTH))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void rejectsValuesAboveTheConfiguredLength() {
        assertThatThrownBy(() -> QueryParameterBinder.bind(List.of(
                new QueryParameter("x", ParameterType.STRING, "a".repeat(11))), 10))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("maximum length");
    }
}
