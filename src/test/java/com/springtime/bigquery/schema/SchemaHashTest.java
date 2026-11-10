package com.springtime.bigquery.schema;

import com.springtime.bigquery.model.query.Column;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaHashTest {

    private static final List<Column> SCHEMA = List.of(
            new Column("id", "STRING", "REQUIRED"),
            new Column("person", "STRUCT", "NULLABLE", List.of(
                    new Column("name", "STRING", "NULLABLE"),
                    new Column("age", "INT64", "NULLABLE"))));

    @Test
    void isStableForEquivalentSchemas() {
        assertThat(SchemaHash.of(SCHEMA))
                .startsWith("sha256:")
                .hasSize("sha256:".length() + 64)
                .isEqualTo(SchemaHash.of(List.copyOf(SCHEMA)));
    }

    @Test
    void doesNotCollideWhenNamesContainSeparators() {
        assertThat(SchemaHash.of(List.of(new Column("a|b", "STRING", "NULLABLE"))))
                .isNotEqualTo(SchemaHash.of(List.of(new Column("a", "b|STRING", "NULLABLE"))));
    }

    @Test
    void changesWhenAnyTypeModeOrNameChanges() {
        String baseline = SchemaHash.of(SCHEMA);

        assertThat(SchemaHash.of(List.of(
                new Column("id", "INT64", "REQUIRED"),
                SCHEMA.get(1))))
                .isNotEqualTo(baseline);
        assertThat(SchemaHash.of(List.of(
                new Column("id", "STRING", "NULLABLE"),
                SCHEMA.get(1))))
                .isNotEqualTo(baseline);
        assertThat(SchemaHash.of(List.of(
                new Column("id", "STRING", "REQUIRED"),
                new Column("person", "STRUCT", "NULLABLE", List.of(
                        new Column("name", "STRING", "NULLABLE"))))))
                .isNotEqualTo(baseline);
    }
}
