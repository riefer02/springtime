package com.springtime.bigquery.model.query;

import java.util.List;

/** Full column list for a single table, plus a stable schema hash. */
public record TableSchema(
        String projectId,
        String datasetId,
        String tableId,
        List<Column> fields,
        String schemaHash
) {
    public TableSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
