package com.springtime.bigquery.retail.event.store;

import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BigQueryDeadLetterStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    private final BigQueryDmlGateway dml = mock(BigQueryDmlGateway.class);
    private final RetailSqlExecutor reads = mock(RetailSqlExecutor.class);
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RetailProperties retailProperties = new RetailProperties(
            "retail_ds", 50, 200, 28, 14, 1_000L, "USD",
            Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    private final BigQueryDeadLetterStore store = new BigQueryDeadLetterStore(
            dml, reads,
            new RetailSqlCatalog(new DefaultResourceLoader(),
                    new BigQueryProperties("project-x", "US", "", 1_000, 100, true, true, 2,
                            Duration.ofSeconds(1), Duration.ofSeconds(10)),
                    retailProperties),
            jsonMapper);

    @Test
    void recordsTheEnvelopeAsJson() {
        DeadLetterEnvelope envelope = envelope();

        store.record(envelope);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, QueryParameterValue>> params = ArgumentCaptor.forClass(Map.class);
        verify(dml).executeIdempotent(anyString(), params.capture(), anyMap());
        assertThat(params.getValue().get("event_id").getValue()).isEqualTo("evt-1");
        assertThat(params.getValue().get("envelope_json").getValue()).contains("\"eventId\":\"evt-1\"");
    }

    @Test
    void mapsStoredRowsBackToEnvelopes() throws Exception {
        String json = jsonMapper.writeValueAsString(envelope());
        when(reads.execute(anyString(), any(), anyLong())).thenReturn(new RetailQueryResult(
                List.of(Map.of(
                        "envelope_json", json,
                        "replayed_at", "2026-11-09T13:00:00Z",
                        "replay_status", "REPLAYED"))));

        assertThat(store.find("evt-1")).get().satisfies(stored -> {
            assertThat(stored.envelope().eventId()).isEqualTo("evt-1");
            assertThat(stored.replayStatus()).isEqualTo("REPLAYED");
        });
    }

    @Test
    void corruptEnvelopeJsonIsAMessagingFailure() {
        when(reads.execute(anyString(), any(), anyLong()))
                .thenReturn(new RetailQueryResult(List.of(Map.of("envelope_json", "not-json"))));

        assertThatThrownBy(() -> store.find("evt-1"))
                .isInstanceOf(MessagingException.class);
    }

    private static DeadLetterEnvelope envelope() {
        EventEnvelope event = new EventEnvelope(
                "evt-1", "SALE", NOW.minusSeconds(60), "1", "ST-001", "SKU-0001",
                2L, Map.of());
        return new DeadLetterEnvelope(
                "evt-1", "SALE", "1", NOW, 1, "boom", "IllegalStateException",
                "retail-events", event);
    }
}
