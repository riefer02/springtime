package com.springtime.bigquery.retail.inventory.sink;

import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.JobException;
import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementType;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BigQueryInventoryMovementSinkTest {

    private static final InventoryMovement MOVEMENT = new InventoryMovement(
            "evt-1", InventoryMovementType.SALE,
            Instant.parse("2026-11-09T12:00:00Z"), LocalDate.of(2026, 11, 9),
            "ST-001", "SKU-0001", -2L, new BigDecimal("1.25"), "USD",
            "retail-api", "evt-1", "1", Map.of("source", "test"));

    private final BigQueryDmlGateway dmlGateway = mock(BigQueryDmlGateway.class);
    private final RetailProperties retailProperties = new RetailProperties(
            "retail_demo", 50, 200, 28, 14, 268_435_456L,
            "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    private final BigQueryInventoryMovementSink sink = new BigQueryInventoryMovementSink(
            dmlGateway,
            new RetailSqlCatalog(new DefaultResourceLoader(),
                    new BigQueryProperties("test-project", "US", "", 1_073_741_824L, 1_000,
                            true, true, 8, Duration.ofSeconds(2), Duration.ofSeconds(60)),
                    retailProperties),
            JsonMapper.builder().build(),
            new SimpleMeterRegistry());

    @Test
    void writesMergeByIdempotencyKeyAndReportsInserted() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap())).thenReturn(1L);

        MovementWriteResult result = sink.write(MOVEMENT);

        assertThat(result).isEqualTo(MovementWriteResult.INSERTED);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, QueryParameterValue>> params =
                ArgumentCaptor.forClass(Map.class);
        verify(dmlGateway).executeIdempotent(sql.capture(), params.capture(), anyMap());
        assertThat(sql.getValue()).contains("MERGE", "inventory_movements", "event_id");
        assertThat(params.getValue().get("event_id").getValue()).isEqualTo("evt-1");
        assertThat(params.getValue().get("quantity_delta").getValue()).isEqualTo("-2");
        assertThat(params.getValue().get("unit_cost").getValue()).isEqualTo("1.25");
        assertThat(params.getValue().get("payload_json").getValue()).contains("\"source\":\"test\"");
    }

    @Test
    void batchMergeSendsArrayParameters() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap())).thenReturn(2L);

        long inserted = sink.writeBatch(List.of(MOVEMENT, MOVEMENT));

        assertThat(inserted).isEqualTo(2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, QueryParameterValue>> params =
                ArgumentCaptor.forClass(Map.class);
        verify(dmlGateway).executeIdempotent(sql.capture(), params.capture(), anyMap());
        assertThat(sql.getValue())
                .contains("UNNEST(GENERATE_ARRAY", "@event_ids", "business_date");
        // Every array element must be read through its named parameter: a bare `event_ids[...]`
        // is a column reference and BigQuery rejects the whole statement at analysis time.
        java.util.Set<String> referenced = new java.util.HashSet<>();
        var matcher = java.util.regex.Pattern.compile("@([a-z_]+)").matcher(sql.getValue());
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
        assertThat(referenced).containsExactlyInAnyOrderElementsOf(params.getValue().keySet());
        assertThat(params.getValue().get("event_ids").getArrayValues())
                .extracting(QueryParameterValue::getValue)
                .contains("evt-1");
        assertThat(params.getValue().get("quantity_deltas").getArrayValues())
                .extracting(QueryParameterValue::getValue)
                .contains("-2");
    }

    @Test
    void duplicateMergeReportsDuplicate() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap())).thenReturn(0L);

        assertThat(sink.write(MOVEMENT)).isEqualTo(MovementWriteResult.DUPLICATE);
    }

    @Test
    void missingLedgerTableIsPermanent() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap()))
                .thenThrow(new BigQueryException(404, "table not found"));

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOfSatisfying(InventoryWriteException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.reason()).isEqualTo("LEDGER_NOT_READY");
                });
    }

    @Test
    void serviceUnavailableIsRetryable() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap()))
                .thenThrow(new BigQueryException(503, "backend unavailable"));

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOfSatisfying(InventoryWriteException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.reason()).isEqualTo("LEDGER_UNAVAILABLE");
                });
    }

    @Test
    void conflictIsRetryable() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap()))
                .thenThrow(new BigQueryException(409, "conflict"));

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOfSatisfying(InventoryWriteException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.reason()).isEqualTo("LEDGER_CONFLICT");
                });
    }

    @Test
    void jobBackendErrorIsRetryable() {
        JobException jobException = mock(JobException.class);
        when(jobException.getErrors()).thenReturn(List.of(
                new BigQueryError("jobBackendError", "us", "temporary backend failure")));
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap())).thenThrow(jobException);

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOfSatisfying(InventoryWriteException.class, exception -> {
                    assertThat(exception.retryable()).isTrue();
                    assertThat(exception.reason()).isEqualTo("LEDGER_UNAVAILABLE");
                });
    }

    @Test
    void rejectedStatementIsPermanent() {
        when(dmlGateway.executeIdempotent(anyString(), anyMap(), anyMap()))
                .thenThrow(new BigQueryException(400, "invalid"));

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOfSatisfying(InventoryWriteException.class, exception -> {
                    assertThat(exception.retryable()).isFalse();
                    assertThat(exception.reason()).isEqualTo("LEDGER_REJECTED");
                });
    }
}
