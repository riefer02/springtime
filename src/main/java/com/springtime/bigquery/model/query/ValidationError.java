package com.springtime.bigquery.model.query;

/** One field-level validation failure. */
public record ValidationError(String field, String message) {
}
