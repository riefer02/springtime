package com.springtime.bigquery.service;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.TableResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryResultMapperTest {

    private final QueryResultMapper mapper = new QueryResultMapper();
    private final Field text = Field.of("text", LegacySQLTypeName.STRING);
    private final Schema schema = Schema.of(text);

    @Test
    void dropsTheFirstRowWhenItAloneExceedsTheByteBudget() {
        TableResult result = result("x".repeat(1_000));

        QueryResultMapper.QueryData data = mapper.map(result, 100);

        assertThat(data.rows()).isEmpty();
        assertThat(data.truncated()).isTrue();
    }

    @Test
    void keepsRowsThatFitAndFlagsTheCut() {
        TableResult result = result("ok", "x".repeat(1_000));

        QueryResultMapper.QueryData data = mapper.map(result, 100);

        assertThat(data.rows()).hasSize(1);
        assertThat(data.rows().get(0)).containsEntry("text", "ok");
        assertThat(data.truncated()).isTrue();
    }

    private TableResult result(String... values) {
        List<FieldValueList> rows = java.util.Arrays.stream(values)
                .map(value -> FieldValueList.of(
                        List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, value)), text))
                .toList();
        TableResult result = mock(TableResult.class);
        when(result.getSchema()).thenReturn(schema);
        when(result.getValues()).thenReturn(rows);
        return result;
    }
}
