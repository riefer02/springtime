package com.springtime.bigquery.retail.query;

import java.util.List;
import java.util.Map;

/** Rows returned by a fixed retail query. Job and cost metadata stay in the executor's log. */
public record RetailQueryResult(List<Map<String, Object>> rows) {

    public RetailQueryResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
