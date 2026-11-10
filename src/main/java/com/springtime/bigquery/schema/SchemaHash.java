package com.springtime.bigquery.schema;

import com.springtime.bigquery.model.query.Column;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic hash of a column tree. Use it as the practical schema version for clients and
 * cache validators; BigQuery remains the authoritative live schema source.
 */
public final class SchemaHash {

    private SchemaHash() {
    }

    public static String of(List<Column> columns) {
        StringBuilder canonical = new StringBuilder();
        append(columns, canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void append(List<Column> columns, StringBuilder target) {
        for (Column column : columns) {
            appendPart(target, column.name());
            appendPart(target, column.type());
            appendPart(target, column.mode());
            if (column.subFields() != null && !column.subFields().isEmpty()) {
                target.append('[');
                append(column.subFields(), target);
                target.append(']');
            }
            target.append(';');
        }
    }

    private static void appendPart(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }
}
