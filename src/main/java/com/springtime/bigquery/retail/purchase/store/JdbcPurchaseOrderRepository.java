package com.springtime.bigquery.retail.purchase.store;

import com.springtime.bigquery.retail.purchase.application.PurchaseOrderRepository;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrder;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderEvent;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLine;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderLineDraft;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Postgres purchase-order control-plane repository. */
@Component
public class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private final JdbcClient jdbcClient;
    private final NamedParameterJdbcTemplate batchTemplate;

    public JdbcPurchaseOrderRepository(JdbcClient jdbcClient,
                                       NamedParameterJdbcTemplate batchTemplate) {
        this.jdbcClient = jdbcClient;
        this.batchTemplate = batchTemplate;
    }

    @Override
    public Optional<PurchaseOrder> find(String poId) {
        return jdbcClient.sql("SELECT * FROM purchase_orders WHERE po_id = :id")
                .param("id", UUID.fromString(poId))
                .query(this::mapOrder)
                .optional();
    }

    @Override
    public Optional<PurchaseOrder> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("SELECT * FROM purchase_orders WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(this::mapOrder)
                .optional();
    }

    @Override
    public Optional<PurchaseOrder> findByScope(String runId, String storeId, String supplierId) {
        return jdbcClient.sql("""
                        SELECT * FROM purchase_orders
                        WHERE run_id = :runId AND store_id = :storeId AND supplier_id = :supplierId
                          AND status <> 'CANCELLED'
                        """)
                .param("runId", runId)
                .param("storeId", storeId)
                .param("supplierId", supplierId)
                .query(this::mapOrder)
                .optional();
    }

    @Override
    public List<PurchaseOrder> list(String storeId, String supplierId,
                                    PurchaseOrderStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM purchase_orders WHERE 1=1");
        if (storeId != null && !storeId.isBlank()) {
            sql.append(" AND store_id = :storeId");
        }
        if (supplierId != null && !supplierId.isBlank()) {
            sql.append(" AND supplier_id = :supplierId");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY created_at DESC, po_id LIMIT :limit OFFSET :offset");
        var spec = jdbcClient.sql(sql.toString())
                .param("limit", limit)
                .param("offset", Math.max(0, offset));
        if (storeId != null && !storeId.isBlank()) {
            spec = spec.param("storeId", storeId);
        }
        if (supplierId != null && !supplierId.isBlank()) {
            spec = spec.param("supplierId", supplierId);
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::mapOrder).list();
    }

    @Override
    public void create(PurchaseOrder order) {
        jdbcClient.sql("""
                INSERT INTO purchase_orders (
                  po_id, po_number, idempotency_key, run_id, store_id, supplier_id,
                  supplier_name, status, currency, total_value, line_count, requested_by,
                  note, expected_delivery_date, supplier_reference, version, created_at
                ) VALUES (
                  :id, :poNumber, :idempotencyKey, :runId, :storeId, :supplierId,
                  :supplierName, :status, :currency, :totalValue, :lineCount, :requestedBy,
                  :note, :expectedDeliveryDate, :supplierReference, :version, :createdAt
                ) ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.fromString(order.poId()))
                .param("poNumber", order.poNumber())
                .param("idempotencyKey", order.idempotencyKey())
                .param("runId", order.runId())
                .param("storeId", order.storeId())
                .param("supplierId", order.supplierId())
                .param("supplierName", order.supplierName())
                .param("status", order.status().name())
                .param("currency", order.currency())
                .param("totalValue", order.totalValue())
                .param("lineCount", order.lineCount())
                .param("requestedBy", order.requestedBy())
                .param("note", order.note())
                .param("expectedDeliveryDate", order.expectedDeliveryDate())
                .param("supplierReference", order.supplierReference())
                .param("version", order.version())
                .param("createdAt", timestamp(order.createdAt()))
                .update();
    }

    @Override
    public void insertLines(PurchaseOrder order, List<PurchaseOrderLineDraft> lines,
                            Instant createdAt) {
        if (lines.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = new SqlParameterSource[lines.size()];
        UUID poId = UUID.fromString(order.poId());
        Timestamp created = timestamp(createdAt);
        for (int index = 0; index < lines.size(); index++) {
            PurchaseOrderLineDraft proposal = lines.get(index);
            batch[index] = new MapSqlParameterSource()
                    .addValue("poId", poId)
                    .addValue("lineNumber", index + 1)
                    .addValue("productId", proposal.productId())
                    .addValue("sku", proposal.sku())
                    .addValue("productName", proposal.productName())
                    .addValue("supplierId", proposal.supplierId())
                    .addValue("orderedUnits", proposal.orderedUnits())
                    .addValue("lineValue", proposal.lineValue())
                    .addValue("proposalVersion", proposal.sourceProposalVersion())
                    .addValue("createdAt", created);
        }
        batchTemplate.batchUpdate("""
                INSERT INTO purchase_order_lines (
                  po_id, line_number, product_id, sku, product_name,
                  supplier_id, ordered_units, line_value, source_proposal_version, created_at
                ) VALUES (
                  :poId, :lineNumber, :productId, :sku, :productName,
                  :supplierId, :orderedUnits, :lineValue, :proposalVersion, :createdAt
                )
                """, batch);
    }

    @Override
    public List<PurchaseOrderLine> lines(String poId, int limit, int offset) {
        return jdbcClient.sql("""
                SELECT * FROM purchase_order_lines
                WHERE po_id = :id
                ORDER BY line_number
                LIMIT :limit OFFSET :offset
                """)
                .param("id", UUID.fromString(poId))
                .param("limit", limit)
                .param("offset", Math.max(0, offset))
                .query(this::mapLine)
                .list();
    }

    @Override
    public int submit(String poId, int expectedVersion, String supplierReference, Instant submittedAt) {
        return jdbcClient.sql("""
                UPDATE purchase_orders
                SET status = 'SUBMITTED', submitted_at = :submittedAt,
                    supplier_reference = :supplierReference, version = version + 1
                WHERE po_id = :id AND status = 'DRAFT' AND version = :version
                """)
                .param("id", UUID.fromString(poId))
                .param("version", expectedVersion)
                .param("supplierReference", supplierReference)
                .param("submittedAt", timestamp(submittedAt))
                .update();
    }

    @Override
    public int cancel(String poId, int expectedVersion, Instant cancelledAt) {
        return jdbcClient.sql("""
                UPDATE purchase_orders
                SET status = 'CANCELLED', cancelled_at = :cancelledAt, version = version + 1
                WHERE po_id = :id AND status = 'DRAFT' AND version = :version
                """)
                .param("id", UUID.fromString(poId))
                .param("version", expectedVersion)
                .param("cancelledAt", timestamp(cancelledAt))
                .update();
    }

    @Override
    public void recordEvent(PurchaseOrderEvent event) {
        jdbcClient.sql("""
                INSERT INTO purchase_order_events (
                  event_id, po_id, from_status, to_status, actor, note, occurred_at
                ) VALUES (
                  :eventId, :poId, :fromStatus, :toStatus, :actor, :note, :occurredAt
                )
                """)
                .param("eventId", UUID.fromString(event.eventId()))
                .param("poId", UUID.fromString(event.poId()))
                .param("fromStatus", event.fromStatus() == null ? null : event.fromStatus().name())
                .param("toStatus", event.toStatus().name())
                .param("actor", event.actor())
                .param("note", event.note())
                .param("occurredAt", timestamp(event.occurredAt()))
                .update();
    }

    @Override
    public List<PurchaseOrderEvent> events(String poId, int limit, int offset) {
        return jdbcClient.sql("""
                SELECT * FROM purchase_order_events
                WHERE po_id = :id
                ORDER BY occurred_at DESC, event_id
                LIMIT :limit OFFSET :offset
                """)
                .param("id", UUID.fromString(poId))
                .param("limit", limit)
                .param("offset", Math.max(0, offset))
                .query(this::mapEvent)
                .list();
    }

    private PurchaseOrder mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new PurchaseOrder(
                rs.getString("po_id"),
                rs.getString("po_number"),
                rs.getString("idempotency_key"),
                rs.getString("run_id"),
                rs.getString("store_id"),
                rs.getString("supplier_id"),
                rs.getString("supplier_name"),
                PurchaseOrderStatus.valueOf(rs.getString("status")),
                rs.getString("currency"),
                rs.getBigDecimal("total_value"),
                rs.getInt("line_count"),
                rs.getString("requested_by"),
                rs.getString("note"),
                date(rs, "expected_delivery_date"),
                rs.getString("supplier_reference"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "submitted_at"),
                instant(rs, "cancelled_at"));
    }

    private PurchaseOrderLine mapLine(ResultSet rs, int rowNum) throws SQLException {
        return new PurchaseOrderLine(
                rs.getString("po_id"),
                rs.getInt("line_number"),
                rs.getString("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("supplier_id"),
                rs.getLong("ordered_units"),
                rs.getBigDecimal("line_value"),
                rs.getInt("source_proposal_version"),
                instant(rs, "created_at"));
    }

    private PurchaseOrderEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        String from = rs.getString("from_status");
        return new PurchaseOrderEvent(
                rs.getString("event_id"),
                rs.getString("po_id"),
                from == null ? null : PurchaseOrderStatus.valueOf(from),
                PurchaseOrderStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor"),
                rs.getString("note"),
                instant(rs, "occurred_at"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static java.time.LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }
}
