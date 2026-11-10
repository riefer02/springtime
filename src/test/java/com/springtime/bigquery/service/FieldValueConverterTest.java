package com.springtime.bigquery.service;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldValueConverterTest {

    @Test
    void convertsStringBackedPrimitivesToTypedJsonValues() {
        Field text = Field.of("text", LegacySQLTypeName.STRING);
        Field count = Field.of("count", LegacySQLTypeName.INTEGER);
        Field ratio = Field.of("ratio", LegacySQLTypeName.FLOAT);
        Field enabled = Field.of("enabled", LegacySQLTypeName.BOOLEAN);
        Field amount = Field.of("amount", LegacySQLTypeName.NUMERIC);
        Field missing = Field.of("missing", LegacySQLTypeName.STRING);
        FieldValueList row = FieldValueList.of(
                List.of(
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "hello"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "42"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1.5"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "true"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "123.4500"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)),
                text, count, ratio, enabled, amount, missing);

        Map<String, Object> converted = FieldValueConverter.toRow(
                row, FieldList.of(text, count, ratio, enabled, amount, missing));

        assertThat(converted)
                .containsEntry("text", "hello")
                .containsEntry("count", 42L)
                .containsEntry("ratio", 1.5)
                .containsEntry("enabled", true)
                .containsKey("missing");
        assertThat(converted.get("amount")).isEqualTo("123.4500");
        assertThat(converted.get("missing")).isNull();
    }

    @Test
    void convertsTimestampToRfc3339AndBytesToBase64() {
        Field timestamp = Field.of("ts", LegacySQLTypeName.TIMESTAMP);
        Field payload = Field.of("payload", LegacySQLTypeName.BYTES);
        FieldValueList row = FieldValueList.of(
                List.of(
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1760000000"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "AQID")),
                timestamp, payload);

        Map<String, Object> converted = FieldValueConverter.toRow(row, FieldList.of(timestamp, payload));

        assertThat(converted.get("ts")).isEqualTo(Instant.ofEpochSecond(1_760_000_000L).toString());
        assertThat(converted.get("payload")).isEqualTo("AQID");
    }

    @Test
    void convertsRepeatedColumns() {
        Field tags = Field.newBuilder("tags", LegacySQLTypeName.STRING)
                .setMode(Field.Mode.REPEATED)
                .build();
        FieldValueList row = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.REPEATED, List.of(
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "a"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "b")))),
                tags);

        Map<String, Object> converted = FieldValueConverter.toRow(row, FieldList.of(tags));

        assertThat(converted.get("tags")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void convertsRecordColumns() {
        Field name = Field.of("name", LegacySQLTypeName.STRING);
        Field age = Field.of("age", LegacySQLTypeName.INTEGER);
        FieldValueList nested = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "Ada"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "36")),
                name, age);
        Field person = Field.of("person", LegacySQLTypeName.RECORD, name, age);
        FieldValueList row = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.RECORD, nested)),
                person);

        Map<String, Object> converted = FieldValueConverter.toRow(row, FieldList.of(person));

        assertThat(converted.get("person")).isEqualTo(Map.of("name", "Ada", "age", 36L));
    }

    @Test
    void convertsRepeatedRecords() {
        Field name = Field.of("name", LegacySQLTypeName.STRING);
        Field people = Field.newBuilder("people", LegacySQLTypeName.RECORD, name)
                .setMode(Field.Mode.REPEATED)
                .build();
        FieldValueList first = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "Ada")), name);
        FieldValueList second = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, "Grace")), name);
        FieldValueList row = FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.REPEATED, List.of(
                        FieldValue.of(FieldValue.Attribute.RECORD, first),
                        FieldValue.of(FieldValue.Attribute.RECORD, second)))),
                people);

        Map<String, Object> converted = FieldValueConverter.toRow(row, FieldList.of(people));

        assertThat(converted.get("people"))
                .isEqualTo(List.of(Map.of("name", "Ada"), Map.of("name", "Grace")));
    }

    @Test
    void disambiguatesDuplicateColumnNames() {
        Field x = Field.of("x", LegacySQLTypeName.INTEGER);
        FieldValueList row = FieldValueList.of(
                List.of(
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "1"),
                        FieldValue.of(FieldValue.Attribute.PRIMITIVE, "2")),
                x, x);

        Map<String, Object> converted = FieldValueConverter.toRow(row, FieldList.of(x, x));

        assertThat(converted).containsEntry("x", 1L).containsEntry("x_2", 2L);
    }
}
