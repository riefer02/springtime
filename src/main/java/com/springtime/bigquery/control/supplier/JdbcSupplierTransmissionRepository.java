package com.springtime.bigquery.control.supplier;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Postgres-backed supplier outbox using explicit SQL and FOR UPDATE SKIP LOCKED. */
@Component
public class JdbcSupplierTransmissionRepository implements SupplierTransmissionRepository {

    private static final String COLUMNS = """
            transmission_id, po_id, supplier_id, status, attempts, next_attempt_at,
            payload::text AS payload, payload_hash, supplier_reference, last_error,
            created_at, updated_at, sent_at, acked_at, version
            """;

    // Reads used by list/get/idempotency checks never expose the payload. Avoid materializing up to
    // 1,000-line JSON documents just to answer a status query.
    private static final String SUMMARY_COLUMNS = """
            transmission_id, po_id, supplier_id, status, attempts, next_attempt_at,
            NULL::text AS payload, payload_hash, supplier_reference, last_error,
            created_at, updated_at, sent_at, acked_at, version
            """;

    private final JdbcClient jdbcClient;

    public JdbcSupplierTransmissionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public SupplierTransmission enqueue(SupplierTransmission transmission) {
        String sql = """
                INSERT INTO supplier_transmissions (
                  transmission_id, po_id, supplier_id, status, attempts, next_attempt_at,
                  payload, payload_hash, supplier_reference, last_error,
                  created_at, updated_at, sent_at, acked_at, version
                ) VALUES (
                  :id, :poId, :supplierId, :status, :attempts, :nextAttemptAt,
                  CAST(:payload AS jsonb), :payloadHash, :supplierReference, :lastError,
                  :createdAt, :updatedAt, :sentAt, :ackedAt, :version
                )
                ON CONFLICT (po_id) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS);
        return jdbcClient.sql(sql)
                .param("id", transmission.transmissionId())
                .param("poId", transmission.poId())
                .param("supplierId", transmission.supplierId())
                .param("status", transmission.status().name())
                .param("attempts", transmission.attempts())
                .param("nextAttemptAt", timestamp(transmission.nextAttemptAt()))
                .param("payload", transmission.payload())
                .param("payloadHash", transmission.payloadHash())
                .param("supplierReference", transmission.supplierReference())
                .param("lastError", transmission.lastError())
                .param("createdAt", timestamp(transmission.createdAt()))
                .param("updatedAt", timestamp(transmission.updatedAt()))
                .param("sentAt", timestamp(transmission.sentAt()))
                .param("ackedAt", timestamp(transmission.ackedAt()))
                .param("version", transmission.version())
                .query(this::map)
                .optional()
                .orElseGet(() -> findByPoId(transmission.poId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Supplier transmission conflict but no row for " + transmission.poId())));
    }

    @Override
    public Optional<SupplierTransmission> find(UUID transmissionId) {
        return jdbcClient.sql("SELECT " + SUMMARY_COLUMNS
                        + " FROM supplier_transmissions WHERE transmission_id = :id")
                .param("id", transmissionId)
                .query(this::map)
                .optional();
    }

    @Override
    public Optional<SupplierTransmission> findByPoId(String poId) {
        return jdbcClient.sql("SELECT " + SUMMARY_COLUMNS
                        + " FROM supplier_transmissions WHERE po_id = :poId")
                .param("poId", poId)
                .query(this::map)
                .optional();
    }

    @Override
    public List<SupplierTransmission> list(SupplierTransmissionStatus status, int limit, int offset) {
        String filter = status == null ? "" : " WHERE status = :status";
        var statement = jdbcClient.sql("SELECT " + SUMMARY_COLUMNS + " FROM supplier_transmissions"
                        + filter + " ORDER BY created_at DESC, transmission_id"
                        + " LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", Math.max(0, offset));
        if (status != null) {
            statement = statement.param("status", status.name());
        }
        return statement.query(this::map).list();
    }

    @Override
    public List<SupplierTransmission> claimDue(int limit) {
        String sql = """
                UPDATE supplier_transmissions
                SET status = 'SENDING', attempts = attempts + 1,
                    updated_at = now(), version = version + 1
                WHERE transmission_id IN (
                  SELECT transmission_id
                  FROM supplier_transmissions
                  WHERE (status = 'PENDING' AND next_attempt_at <= now())
                     OR (status = 'SENDING' AND updated_at < now() - INTERVAL '10 minutes')
                  ORDER BY next_attempt_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT :limit
                )
                RETURNING %s
                """.formatted(COLUMNS);
        return jdbcClient.sql(sql).param("limit", limit).query(this::map).list();
    }

    @Override
    public int markSent(UUID transmissionId, long expectedVersion, String supplierReference,
                        Instant sentAt) {
        return jdbcClient.sql("""
                UPDATE supplier_transmissions
                SET status = 'SENT', supplier_reference = :reference, sent_at = :sentAt,
                    updated_at = now(), version = version + 1
                WHERE transmission_id = :id AND status = 'SENDING' AND version = :version
                """)
                .param("id", transmissionId)
                .param("version", expectedVersion)
                .param("reference", supplierReference)
                .param("sentAt", timestamp(sentAt))
                .update();
    }

    @Override
    public int markRetry(UUID transmissionId, long expectedVersion, String error,
                         Instant nextAttemptAt) {
        return jdbcClient.sql("""
                UPDATE supplier_transmissions
                SET status = 'PENDING', last_error = :error, next_attempt_at = :nextAttemptAt,
                    updated_at = now(), version = version + 1
                WHERE transmission_id = :id AND status = 'SENDING' AND version = :version
                """)
                .param("id", transmissionId)
                .param("version", expectedVersion)
                .param("error", truncate(error))
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .update();
    }

    @Override
    public int markFailed(UUID transmissionId, long expectedVersion, String error) {
        return jdbcClient.sql("""
                UPDATE supplier_transmissions
                SET status = 'FAILED', last_error = :error,
                    updated_at = now(), version = version + 1
                WHERE transmission_id = :id AND status = 'SENDING' AND version = :version
                """)
                .param("id", transmissionId)
                .param("version", expectedVersion)
                .param("error", truncate(error))
                .update();
    }

    @Override
    public int markAcked(UUID transmissionId, long expectedVersion, String supplierReference,
                         Instant ackedAt, String actor) {
        return jdbcClient.sql("""
                UPDATE supplier_transmissions
                SET status = 'ACKED',
                    supplier_reference = COALESCE(CAST(:reference AS VARCHAR), supplier_reference),
                    acked_at = :ackedAt, acked_by = :actor,
                    updated_at = now(), version = version + 1
                WHERE transmission_id = :id AND status = 'SENT' AND version = :version
                """)
                .param("id", transmissionId)
                .param("version", expectedVersion)
                .param("reference", supplierReference)
                .param("ackedAt", timestamp(ackedAt))
                .param("actor", truncate(actor))
                .update();
    }

    @Override
    public int retry(UUID transmissionId, long expectedVersion, Instant nextAttemptAt,
                     String actor) {
        return jdbcClient.sql("""
                UPDATE supplier_transmissions
                SET status = 'PENDING', next_attempt_at = :nextAttemptAt, last_error = NULL,
                    attempts = 0, retried_by = :actor,
                    updated_at = now(), version = version + 1
                WHERE transmission_id = :id
                  AND status = 'FAILED'
                  AND version = :version
                """)
                .param("id", transmissionId)
                .param("version", expectedVersion)
                .param("nextAttemptAt", timestamp(nextAttemptAt))
                .param("actor", truncate(actor))
                .update();
    }

    private SupplierTransmission map(ResultSet rs, int rowNum) throws SQLException {
        return new SupplierTransmission(
                UUID.fromString(rs.getString("transmission_id")),
                rs.getString("po_id"),
                rs.getString("supplier_id"),
                SupplierTransmissionStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                instant(rs, "next_attempt_at"),
                rs.getString("payload"),
                rs.getString("payload_hash"),
                rs.getString("supplier_reference"),
                rs.getString("last_error"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "sent_at"),
                instant(rs, "acked_at"),
                rs.getLong("version"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
