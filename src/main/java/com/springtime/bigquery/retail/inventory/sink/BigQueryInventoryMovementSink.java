package com.springtime.bigquery.retail.inventory.sink;

import com.springtime.bigquery.retail.inventory.application.InventoryMovementBatchSink;
import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.JobException;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.util.Timestamps;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes one movement with a single {@code MERGE} keyed by {@code event_id}. A duplicate event
 * produces zero affected rows and is reported as {@link MovementWriteResult#DUPLICATE}; the
 * processor can then acknowledge without applying the event twice.
 */
@Component
public class BigQueryInventoryMovementSink implements InventoryMovementBatchSink {


    private final BigQueryDmlGateway dmlGateway;
    private final RetailSqlCatalog sqlCatalog;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    public BigQueryInventoryMovementSink(BigQueryDmlGateway dmlGateway,
                                         RetailSqlCatalog sqlCatalog,
                                         JsonMapper jsonMapper,
                                         MeterRegistry meterRegistry) {
        this.dmlGateway = dmlGateway;
        this.sqlCatalog = sqlCatalog;
        this.jsonMapper = jsonMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public MovementWriteResult write(InventoryMovement movement) {
        long started = System.nanoTime();
        try {
            String sql = sqlCatalog.sql("inventory_movement_merge");
            long affected = dmlGateway.executeIdempotent(
                    sql, parameters(movement), labels(movement));
            return affected > 0 ? MovementWriteResult.INSERTED : MovementWriteResult.DUPLICATE;
        } catch (BigQueryException exception) {
            throw map(exception);
        } catch (JobException exception) {
            throw map(exception);
        } catch (IllegalStateException exception) {
            throw new InventoryWriteException("LEDGER_UNAVAILABLE",
                    "Inventory ledger write did not produce a confirmed result", true, exception);
        } finally {
            Timer.builder("retail.inventory.movement.write")
                    .tag("movement_type", movement.movementType().name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    @Override
    public long writeBatch(List<InventoryMovement> movements) {
        if (movements.isEmpty()) {
            return 0L;
        }
        long started = System.nanoTime();
        try {
            String sql = sqlCatalog.sql("inventory_movement_merge_batch");
            return dmlGateway.executeIdempotent(
                    sql, batchParameters(movements), batchLabels(movements.size()));
        } catch (BigQueryException exception) {
            throw map(exception);
        } catch (JobException exception) {
            throw map(exception);
        } catch (IllegalStateException exception) {
            throw new InventoryWriteException("LEDGER_UNAVAILABLE",
                    "Inventory ledger batch write did not produce a confirmed result", true, exception);
        } finally {
            DistributionSummary.builder("retail.inventory.movement.batch.size")
                    .register(meterRegistry)
                    .record(movements.size());
            Timer.builder("retail.inventory.movement.batch")
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private Map<String, QueryParameterValue> batchParameters(List<InventoryMovement> movements) {
        List<String> eventIds = new ArrayList<>(movements.size());
        List<String> movementTypes = new ArrayList<>(movements.size());
        List<String> eventTimes = new ArrayList<>(movements.size());
        List<String> businessDates = new ArrayList<>(movements.size());
        List<String> storeIds = new ArrayList<>(movements.size());
        List<String> productIds = new ArrayList<>(movements.size());
        List<Long> quantityDeltas = new ArrayList<>(movements.size());
        List<String> unitCosts = new ArrayList<>(movements.size());
        List<String> currencies = new ArrayList<>(movements.size());
        List<String> sourceSystems = new ArrayList<>(movements.size());
        List<String> sourceDocumentIds = new ArrayList<>(movements.size());
        List<String> schemaVersions = new ArrayList<>(movements.size());
        List<String> payloads = new ArrayList<>(movements.size());
        for (InventoryMovement movement : movements) {
            eventIds.add(movement.eventId());
            movementTypes.add(movement.movementType().name());
            eventTimes.add(Timestamps.bigQuery(movement.occurredAt()));
            businessDates.add(movement.businessDate().toString());
            storeIds.add(movement.storeId());
            productIds.add(movement.productId());
            quantityDeltas.add(movement.quantityDelta());
            unitCosts.add(movement.unitCost() == null ? "" : movement.unitCost().toPlainString());
            currencies.add(movement.currency());
            sourceSystems.add(movement.sourceSystem());
            sourceDocumentIds.add(movement.sourceDocumentId());
            schemaVersions.add(movement.schemaVersion());
            payloads.add(payloadJson(movement));
        }
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("event_ids", QueryParameterValue.array(
                eventIds.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("movement_types", QueryParameterValue.array(
                movementTypes.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("event_times", QueryParameterValue.array(
                eventTimes.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("business_dates", QueryParameterValue.array(
                businessDates.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("store_ids", QueryParameterValue.array(
                storeIds.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("product_ids", QueryParameterValue.array(
                productIds.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("quantity_deltas", QueryParameterValue.array(
                quantityDeltas.toArray(Long[]::new), StandardSQLTypeName.INT64));
        parameters.put("unit_costs", QueryParameterValue.array(
                unitCosts.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("currencies", QueryParameterValue.array(
                currencies.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("source_systems", QueryParameterValue.array(
                sourceSystems.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("source_document_ids", QueryParameterValue.array(
                sourceDocumentIds.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("schema_versions", QueryParameterValue.array(
                schemaVersions.toArray(String[]::new), StandardSQLTypeName.STRING));
        parameters.put("payload_jsons", QueryParameterValue.array(
                payloads.toArray(String[]::new), StandardSQLTypeName.STRING));
        return parameters;
    }

    private static Map<String, String> batchLabels(int size) {
        return com.springtime.bigquery.observability.JobLabels.of(
                "inventory-ledger-batch", Map.of("batch_size", Integer.toString(size)));
    }

    private Map<String, QueryParameterValue> parameters(InventoryMovement movement) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("event_id", QueryParameterValue.string(movement.eventId()));
        parameters.put("movement_type", QueryParameterValue.string(movement.movementType().name()));
        parameters.put("event_time", QueryParameterValue.string(Timestamps.bigQuery(movement.occurredAt())));
        parameters.put("business_date", QueryParameterValue.date(movement.businessDate().toString()));
        parameters.put("store_id", QueryParameterValue.string(movement.storeId()));
        parameters.put("product_id", QueryParameterValue.string(movement.productId()));
        parameters.put("quantity_delta", QueryParameterValue.int64(movement.quantityDelta()));
        parameters.put("unit_cost", QueryParameterValue.string(
                movement.unitCost() == null ? "" : movement.unitCost().toPlainString()));
        parameters.put("currency", QueryParameterValue.string(movement.currency()));
        parameters.put("source_system", QueryParameterValue.string(movement.sourceSystem()));
        parameters.put("source_document_id", QueryParameterValue.string(movement.sourceDocumentId()));
        parameters.put("schema_version", QueryParameterValue.string(movement.schemaVersion()));
        parameters.put("payload_json", QueryParameterValue.string(payloadJson(movement)));
        return parameters;
    }

    private String payloadJson(InventoryMovement movement) {
        try {
            return jsonMapper.writeValueAsString(movement.payload());
        } catch (RuntimeException exception) {
            throw new InventoryWriteException("LEDGER_PAYLOAD_INVALID",
                    "Unable to serialize movement payload", false, exception);
        }
    }

    private Map<String, String> labels(InventoryMovement movement) {
        Map<String, String> labels = new HashMap<>();
        return com.springtime.bigquery.observability.JobLabels.of(
                "inventory-ledger",
                Map.of("movement_type", movement.movementType().name()));
    }

    private static InventoryWriteException map(BigQueryException exception) {
        int code = exception.getCode();
        boolean retryable = code == 408 || code == 409 || code == 429 || code >= 500;
        String reason = switch (code) {
            case 400 -> "LEDGER_REJECTED";
            case 401, 403 -> "LEDGER_AUTH_FAILED";
            case 404 -> "LEDGER_NOT_READY";
            case 409 -> "LEDGER_CONFLICT";
            case 429 -> "LEDGER_RATE_LIMITED";
            default -> retryable ? "LEDGER_UNAVAILABLE" : "LEDGER_REJECTED";
        };
        return new InventoryWriteException(reason,
                "Inventory ledger write failed with upstream code " + code, retryable, exception);
    }

    private static InventoryWriteException map(JobException exception) {
        var errors = exception.getErrors();
        String reason = errors == null || errors.isEmpty() ? "" : errors.get(0).getReason();
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        boolean retryable = normalized.contains("ratelimit")
                || normalized.contains("quota")
                || normalized.contains("backend")
                || normalized.contains("internal")
                || normalized.contains("unavailable")
                || normalized.contains("stopped")
                || normalized.contains("timeout");
        String code = retryable ? "LEDGER_UNAVAILABLE" : "LEDGER_REJECTED";
        return new InventoryWriteException(code,
                "Inventory ledger job failed with reason " + reason, retryable, exception);
    }
}
