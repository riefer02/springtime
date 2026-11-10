package com.springtime.bigquery.retail.event.store;

import com.springtime.bigquery.retail.event.application.DeadLetterSummary;
import com.springtime.bigquery.retail.event.application.StoredDeadLetter;
import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.event.dto.DeadLetterEnvelope;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.retail.event.application.DeadLetterStore;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import com.springtime.bigquery.util.RowValues;
import com.springtime.bigquery.util.Timestamps;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable dead-letter store backed by BigQuery. */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "true")
public class BigQueryDeadLetterStore implements DeadLetterStore {

    private final BigQueryDmlGateway dmlGateway;
    private final RetailSqlExecutor reads;
    private final RetailSqlCatalog sqlCatalog;
    private final JsonMapper jsonMapper;

    public BigQueryDeadLetterStore(BigQueryDmlGateway dmlGateway,
                                   RetailSqlExecutor reads,
                                   RetailSqlCatalog sqlCatalog,
                                   JsonMapper jsonMapper) {
        this.dmlGateway = dmlGateway;
        this.reads = reads;
        this.sqlCatalog = sqlCatalog;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void record(DeadLetterEnvelope envelope) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("event_id", QueryParameterValue.string(envelope.eventId()));
        parameters.put("event_type", QueryParameterValue.string(envelope.eventType()));
        parameters.put("schema_version", QueryParameterValue.string(envelope.schemaVersion()));
        parameters.put("failed_at", QueryParameterValue.string(Timestamps.bigQuery(envelope.failedAt())));
        parameters.put("attempts", QueryParameterValue.int64(envelope.attempts()));
        parameters.put("reason", QueryParameterValue.string(envelope.reason()));
        parameters.put("error_type", QueryParameterValue.string(
                envelope.errorType() == null ? "" : envelope.errorType()));
        parameters.put("original_topic", QueryParameterValue.string(envelope.originalTopic()));
        parameters.put("envelope_json", QueryParameterValue.string(envelopeJson(envelope)));
        dmlGateway.executeIdempotent(sqlCatalog.sql("dead_letter_merge"), parameters, labels());
    }

    @Override
    public List<DeadLetterSummary> list(int limit, int offset) {
        int boundedLimit = Math.max(1, limit);
        RetailQueryResult response = reads.execute("dead_letter_list",
                List.of(
                        new QueryParameter("row_limit", ParameterType.INT64, boundedLimit),
                        new QueryParameter("row_offset", ParameterType.INT64, Math.max(0, offset))),
                boundedLimit);
        return response.rows().stream().map(BigQueryDeadLetterStore::toSummary).toList();
    }

    @Override
    public Optional<StoredDeadLetter> find(String eventId) {
        RetailQueryResult response = reads.execute("dead_letter_find",
                List.of(new QueryParameter("event_id", ParameterType.STRING, eventId)), 1L);
        return response.rows().stream().findFirst().map(this::toStored);
    }

    @Override
    public void markReplayed(String eventId, Instant replayedAt) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("event_id", QueryParameterValue.string(eventId));
        parameters.put("replayed_at", QueryParameterValue.string(Timestamps.bigQuery(replayedAt)));
        dmlGateway.execute(sqlCatalog.sql("dead_letter_mark_replayed"), parameters, labels());
    }

    private static DeadLetterSummary toSummary(Map<String, Object> row) {
        return new DeadLetterSummary(
                RowValues.string(row, "event_id"),
                RowValues.string(row, "event_type"),
                RowValues.string(row, "schema_version"),
                RowValues.instant(row, "failed_at"),
                (int) RowValues.longValue(row, "attempts"),
                RowValues.string(row, "reason"),
                RowValues.string(row, "error_type"),
                RowValues.string(row, "original_topic"),
                RowValues.instant(row, "replayed_at"),
                RowValues.string(row, "replay_status"));
    }

    private StoredDeadLetter toStored(Map<String, Object> row) {
        DeadLetterEnvelope envelope = deserialize(RowValues.string(row, "envelope_json"));
        Instant replayedAt = RowValues.instant(row, "replayed_at");
        String status = RowValues.string(row, "replay_status");
        return new StoredDeadLetter(envelope, replayedAt,
                status == null ? StoredDeadLetter.PENDING : status);
    }

    private String envelopeJson(DeadLetterEnvelope envelope) {
        try {
            return jsonMapper.writeValueAsString(envelope);
        } catch (RuntimeException exception) {
            throw new MessagingException("Unable to serialize dead-letter envelope", exception);
        }
    }

    private DeadLetterEnvelope deserialize(String json) {
        try {
            return jsonMapper.readValue(json, DeadLetterEnvelope.class);
        } catch (RuntimeException exception) {
            throw new MessagingException("Unable to deserialize dead-letter envelope", exception);
        }
    }

    private static Map<String, String> labels() {
        return com.springtime.bigquery.observability.JobLabels.of("dead-letter-store");
    }
}
