package com.springtime.bigquery.model.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One column of a result set or table schema. RECORD columns carry their nested fields. */
public record Column(
        String name,
        String type,
        String mode,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Column> subFields
) {
    public Column {
        subFields = subFields == null ? List.of() : List.copyOf(subFields);
    }

    public Column(String name, String type, String mode) {
        this(name, type, mode, List.of());
    }
}
