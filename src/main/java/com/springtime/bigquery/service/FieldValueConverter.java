package com.springtime.bigquery.service;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Range;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts BigQuery's row representation into JSON-friendly maps.
 *
 * <p>BigQuery does not expose column names on {@link FieldValue}, and the REST client stores every
 * primitive cell as a string (see {@code FieldValue.fromPb}). The schema therefore drives both the
 * names and the typed conversion: INT64/FLOAT64/BOOL/NUMERIC become JSON numbers/booleans, TIMESTAMP
 * becomes RFC 3339, BYTES becomes base64, and duplicate column names are disambiguated.</p>
 */
final class FieldValueConverter {

    private FieldValueConverter() {
    }

    static Map<String, Object> toRow(FieldValueList row, FieldList fields) {
        return toRow(row, fields, uniqueNames(fields));
    }

    /** Row mapping with the schema names computed once per page instead of once per row. */
    static Map<String, Object> toRow(FieldValueList row, FieldList fields, List<String> names) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            Field field = fieldAt(fields, i);
            values.put(nameAt(names, i), toValue(row.get(i), field));
        }
        return values;
    }

    static Object toValue(FieldValue value, Field field) {
        if (value.isNull()) {
            return null;
        }
        return switch (value.getAttribute()) {
            case REPEATED -> repeatedValue(value, field);
            case RECORD -> recordValue(value.getRecordValue(), subFieldsOf(field));
            case RANGE -> rangeValue(value.getRangeValue());
            default -> primitiveValue(value, field);
        };
    }

    /**
     * Returns the schema names in order, suffixing duplicates ({@code x}, {@code x_2}, ...) so rows
     * and the advertised schema stay aligned.
     */
    static List<String> uniqueNames(FieldList fields) {
        if (fields == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(fields.size());
        // O(columns): remember the next free suffix per base name instead of rescanning the set.
        Map<String, Integer> nextSuffix = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            String base = field == null || field.getName() == null ? "column_" + i : field.getName();
            String candidate = base;
            int suffix = nextSuffix.getOrDefault(base, 2);
            while (!used.add(candidate)) {
                candidate = base + "_" + suffix++;
            }
            nextSuffix.put(base, suffix);
            names.add(candidate);
        }
        return names;
    }

    private static List<Object> repeatedValue(FieldValue value, Field field) {
        List<Object> elements = new ArrayList<>();
        for (FieldValue element : value.getRepeatedValue()) {
            if (element.isNull()) {
                elements.add(null);
            } else if (element.getAttribute() == FieldValue.Attribute.RECORD) {
                elements.add(recordValue(element.getRecordValue(), subFieldsOf(field)));
            } else if (element.getAttribute() == FieldValue.Attribute.RANGE) {
                elements.add(rangeValue(element.getRangeValue()));
            } else {
                elements.add(primitiveValue(element, field));
            }
        }
        return elements;
    }

    private static Map<String, Object> recordValue(FieldValueList values, FieldList fields) {
        List<String> names = uniqueNames(fields);
        Map<String, Object> nested = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            Field field = fieldAt(fields, i);
            nested.put(nameAt(names, i), toValue(values.get(i), field));
        }
        return nested;
    }

    private static Map<String, Object> rangeValue(Range range) {
        Map<String, Object> values = new LinkedHashMap<>(range.getValues());
        if (range.getType() != null) {
            values.put("type", range.getType().getType());
        }
        return values;
    }

    private static Object primitiveValue(FieldValue value, Field field) {
        LegacySQLTypeName type = field == null ? null : field.getType();
        if (type == null || type.getStandardType() == null) {
            return value.getStringValue();
        }
        return switch (type.getStandardType()) {
            case INT64 -> value.getLongValue();
            case FLOAT64 -> value.getDoubleValue();
            case BOOL -> value.getBooleanValue();
            case NUMERIC, BIGNUMERIC -> value.getStringValue();
            case BYTES -> Base64.getEncoder().encodeToString(value.getBytesValue());
            case TIMESTAMP -> value.getTimestampInstant().toString();
            // STRING, DATE, TIME, DATETIME, GEOGRAPHY, JSON and INTERVAL use their textual form.
            default -> value.getStringValue();
        };
    }

    private static Field fieldAt(FieldList fields, int index) {
        if (fields == null || index >= fields.size()) {
            return null;
        }
        return fields.get(index);
    }

    private static FieldList subFieldsOf(Field field) {
        return field == null ? null : field.getSubFields();
    }

    private static String nameAt(List<String> names, int index) {
        return index < names.size() ? names.get(index) : "column_" + index;
    }
}
