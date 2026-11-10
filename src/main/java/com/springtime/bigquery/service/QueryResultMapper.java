package com.springtime.bigquery.service;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.TableResult;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.util.ByteSizes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts BigQuery result pages and schemas into the API row/column model. */
@Component
public class QueryResultMapper {

    public QueryData map(TableResult result, long maxBytes) {
        Schema schema = result.getSchema();
        FieldList fields = schema == null ? null : schema.getFields();
        List<Map<String, Object>> rows = new ArrayList<>();
        long estimatedBytes = 0;
        boolean truncated = false;
        if (fields != null) {
            List<String> names = FieldValueConverter.uniqueNames(fields);
            for (var row : result.getValues()) {
                Map<String, Object> mapped = FieldValueConverter.toRow(row, fields, names);
                long size = ByteSizes.estimate(mapped);
                // Includes the first row: a single oversized cell must not bypass the budget.
                if (estimatedBytes + size > maxBytes) {
                    truncated = true;
                    break;
                }
                rows.add(mapped);
                estimatedBytes += size;
            }
        }
        return new QueryData(columns(schema), rows, truncated);
    }

    public List<Column> columns(Schema schema) {
        return schema == null ? List.of() : columns(schema.getFields());
    }

    private static List<Column> columns(FieldList fields) {
        List<String> names = FieldValueConverter.uniqueNames(fields);
        List<Column> columns = new ArrayList<>(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            List<Column> subFields = field.getSubFields() == null
                    ? List.of()
                    : columns(field.getSubFields());
            columns.add(new Column(
                    names.get(i),
                    typeName(field),
                    field.getMode() == null ? null : field.getMode().name(),
                    subFields));
        }
        return columns;
    }

    private static String typeName(Field field) {
        if (field.getType() == null) {
            return null;
        }
        return field.getType().getStandardType() == null
                ? field.getType().name()
                : field.getType().getStandardType().name();
    }

    public record QueryData(List<Column> schema, List<Map<String, Object>> rows,
                            boolean truncated) {
    }
}
