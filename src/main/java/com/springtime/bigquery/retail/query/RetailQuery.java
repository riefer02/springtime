package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.model.QueryParameter;

import java.util.List;

/** A fixed, read-only retail SQL query with named parameters. */
public record RetailQuery(String sqlName, List<QueryParameter> parameters, int maxResults) {
    public RetailQuery {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
