package com.springtime.bigquery.model.query;

import java.util.List;
import java.util.Map;

/** A page of results for an existing BigQuery job; {@code truncated} means rows were dropped. */
public record QueryResultPage(
        String jobId,
        List<Column> schema,
        List<Map<String, Object>> rows,
        long returnedRows,
        Long totalRows,
        String nextPageToken,
        boolean truncated
) {
    public QueryResultPage {
        schema = schema == null ? List.of() : List.copyOf(schema);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
