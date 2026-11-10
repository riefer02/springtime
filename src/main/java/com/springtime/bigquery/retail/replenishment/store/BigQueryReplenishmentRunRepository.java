package com.springtime.bigquery.retail.replenishment.store;

import com.google.cloud.bigquery.QueryParameterValue;
import com.springtime.bigquery.gateway.BigQueryDmlGateway;
import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.retail.replenishment.application.ReplenishmentRunRepository;
import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ProposalStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunSummary;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.retail.query.RetailSqlCatalog;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import com.springtime.bigquery.util.RowValues;
import com.springtime.bigquery.util.Timestamps;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** BigQuery implementation of the replenishment run store. */
@Component
public class BigQueryReplenishmentRunRepository implements ReplenishmentRunRepository {

    private final BigQueryDmlGateway dmlGateway;
    private final RetailSqlExecutor reads;
    private final RetailSqlCatalog sqlCatalog;

    public BigQueryReplenishmentRunRepository(BigQueryDmlGateway dmlGateway,
                                              RetailSqlExecutor reads,
                                              RetailSqlCatalog sqlCatalog) {
        this.dmlGateway = dmlGateway;
        this.reads = reads;
        this.sqlCatalog = sqlCatalog;
    }

    @Override
    public void begin(ReplenishmentRun run) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("run_id", QueryParameterValue.string(run.runId()));
        parameters.put("store_id", QueryParameterValue.string(run.storeId()));
        parameters.put("as_of_date", QueryParameterValue.date(run.asOfDate().toString()));
        parameters.put("demand_window_days", QueryParameterValue.int64(run.demandWindowDays()));
        parameters.put("target_cover_days", QueryParameterValue.int64(run.targetCoverDays()));
        parameters.put("requested_by", QueryParameterValue.string(
                run.requestedBy() == null ? "" : run.requestedBy()));
        parameters.put("created_at", QueryParameterValue.string(Timestamps.bigQuery(run.createdAt())));
        parameters.put("started_at", QueryParameterValue.string(
                Timestamps.bigQuery(run.startedAt() == null ? run.createdAt() : run.startedAt())));
        execute("replenishment_run_begin", parameters);
    }

    @Override
    public void clearProposals(String runId) {
        execute("replenishment_proposals_clear",
                Map.of("run_id", QueryParameterValue.string(runId)));
    }

    @Override
    public boolean hasDecisions(String runId) {
        RetailQueryResult response = reads.execute("replenishment_run_has_decisions",
                List.of(new QueryParameter("run_id", ParameterType.STRING, runId)), 1L);
        if (response.rows().isEmpty()) {
            return false;
        }
        return RowValues.longValue(response.rows().get(0), "decided_count") > 0;
    }

    @Override
    public void mergeProposals(ReplenishmentRun run, int rowLimit) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("run_id", QueryParameterValue.string(run.runId()));
        parameters.put("store_id", QueryParameterValue.string(run.storeId()));
        parameters.put("as_of_date", QueryParameterValue.date(run.asOfDate().toString()));
        parameters.put("demand_days", QueryParameterValue.int64(run.demandWindowDays()));
        parameters.put("target_cover_days", QueryParameterValue.int64(run.targetCoverDays()));
        parameters.put("row_limit", QueryParameterValue.int64(rowLimit));
        execute("replenishment_proposals_merge", parameters);
    }

    @Override
    public ReplenishmentRunSummary summary(String runId) {
        RetailQueryResult response = reads.execute("replenishment_run_summary",
                List.of(new QueryParameter("run_id", ParameterType.STRING, runId)), 1L);
        if (response.rows().isEmpty()) {
            return ReplenishmentRunSummary.empty();
        }
        var row = response.rows().get(0);
        BigDecimal total = RowValues.bigDecimal(row, "total_order_value");
        return new ReplenishmentRunSummary(
                (int) RowValues.longValue(row, "proposal_count"),
                total == null ? BigDecimal.ZERO : total);
    }

    @Override
    public void complete(String runId, ReplenishmentRunSummary summary, Instant completedAt) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("run_id", QueryParameterValue.string(runId));
        parameters.put("proposal_count", QueryParameterValue.int64(summary.proposalCount()));
        parameters.put("total_order_value", QueryParameterValue.string(
                summary.totalOrderValue().toPlainString()));
        parameters.put("completed_at", QueryParameterValue.string(Timestamps.bigQuery(completedAt)));
        execute("replenishment_run_complete", parameters);
    }

    @Override
    public void fail(String runId, String errorMessage, Instant completedAt) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("run_id", QueryParameterValue.string(runId));
        parameters.put("error_message", QueryParameterValue.string(errorMessage));
        parameters.put("completed_at", QueryParameterValue.string(Timestamps.bigQuery(completedAt)));
        execute("replenishment_run_fail", parameters);
    }

    @Override
    public Optional<ReplenishmentRun> find(String runId) {
        RetailQueryResult response = reads.execute("replenishment_run_find",
                List.of(new QueryParameter("run_id", ParameterType.STRING, runId)), 1L);
        return response.rows().stream().findFirst().map(this::toRun);
    }

    @Override
    public List<ReplenishmentRun> list(String storeId, int limit, int offset) {
        RetailQueryResult response = reads.execute("replenishment_runs_list",
                List.of(
                        new QueryParameter("store_id", ParameterType.STRING, storeId == null ? "" : storeId),
                        new QueryParameter("row_limit", ParameterType.INT64, limit),
                        new QueryParameter("row_offset", ParameterType.INT64, offset)),
                limit);
        return response.rows().stream().map(this::toRun).toList();
    }

    @Override
    public List<ReplenishmentProposal> proposals(String runId, int limit, int offset) {
        RetailQueryResult response = reads.execute("replenishment_proposals_list",
                List.of(
                        new QueryParameter("run_id", ParameterType.STRING, runId),
                        new QueryParameter("row_limit", ParameterType.INT64, limit),
                        new QueryParameter("row_offset", ParameterType.INT64, offset)),
                limit);
        return response.rows().stream().map(this::toProposal).toList();
    }

    @Override
    public Optional<ReplenishmentProposal> findProposal(String runId, String productId) {
        RetailQueryResult response = reads.execute("replenishment_proposal_find",
                List.of(
                        new QueryParameter("run_id", ParameterType.STRING, runId),
                        new QueryParameter("product_id", ParameterType.STRING, productId)),
                1L);
        return response.rows().stream().findFirst().map(this::toProposal);
    }

    @Override
    public void decideProposal(ProposalDecision decision) {
        Map<String, QueryParameterValue> parameters = new HashMap<>();
        parameters.put("decision_id", QueryParameterValue.string(decision.decisionId()));
        parameters.put("run_id", QueryParameterValue.string(decision.runId()));
        parameters.put("store_id", QueryParameterValue.string(decision.storeId()));
        parameters.put("product_id", QueryParameterValue.string(decision.productId()));
        parameters.put("from_status", QueryParameterValue.string(decision.fromStatus().name()));
        parameters.put("to_status", QueryParameterValue.string(decision.toStatus().name()));
        parameters.put("expected_version", QueryParameterValue.int64(decision.expectedVersion()));
        parameters.put("decided_by", QueryParameterValue.string(
                decision.decidedBy() == null ? "" : decision.decidedBy()));
        parameters.put("decision_note", QueryParameterValue.string(
                decision.note() == null ? "" : decision.note()));
        parameters.put("note", QueryParameterValue.string(
                decision.note() == null ? "" : decision.note()));
        parameters.put("order_reference", QueryParameterValue.string(
                decision.orderReference() == null ? "" : decision.orderReference()));
        parameters.put("decided_at", QueryParameterValue.string(Timestamps.bigQuery(decision.decidedAt())));
        dmlGateway.executeTransaction(sqlCatalog.sql("replenishment_proposal_decide"), parameters,
                labels("replenishment-proposal-decision"));
    }

    @Override
    public List<ProposalDecision> decisions(String runId, String productId, int limit, int offset) {
        RetailQueryResult response = reads.execute("replenishment_proposal_decisions_list",
                List.of(
                        new QueryParameter("run_id", ParameterType.STRING, runId),
                        new QueryParameter("product_id", ParameterType.STRING, productId),
                        new QueryParameter("row_limit", ParameterType.INT64, limit),
                        new QueryParameter("row_offset", ParameterType.INT64, offset)),
                limit);
        return response.rows().stream().map(this::toDecision).toList();
    }

    private ReplenishmentRun toRun(Map<String, Object> row) {
        Object proposalCount = row.get("proposal_count");
        return new ReplenishmentRun(
                RowValues.string(row, "run_id"),
                RowValues.string(row, "store_id"),
                RowValues.date(row, "as_of_date"),
                ReplenishmentRunStatus.valueOf(RowValues.string(row, "status")),
                (int) RowValues.longValue(row, "demand_window_days"),
                (int) RowValues.longValue(row, "target_cover_days"),
                RowValues.string(row, "requested_by"),
                RowValues.instant(row, "created_at"),
                RowValues.instant(row, "started_at"),
                RowValues.instant(row, "completed_at"),
                proposalCount == null ? null : (int) RowValues.longValue(row, "proposal_count"),
                RowValues.bigDecimal(row, "total_order_value"),
                RowValues.string(row, "error_message"));
    }

    private ReplenishmentProposal toProposal(Map<String, Object> row) {
        return new ReplenishmentProposal(
                RowValues.string(row, "run_id"),
                RowValues.date(row, "as_of_date"),
                RowValues.string(row, "store_id"),
                RowValues.string(row, "product_id"),
                RowValues.string(row, "sku"),
                RowValues.string(row, "product_name"),
                RowValues.string(row, "category"),
                RowValues.string(row, "supplier_id"),
                RowValues.string(row, "supplier_name"),
                RowValues.longValue(row, "available_units"),
                RowValues.doubleValueOrZero(row, "avg_daily_units"),
                RowValues.doubleValue(row, "days_of_supply"),
                RowValues.longValue(row, "lead_time_days"),
                RowValues.longValue(row, "effective_reorder_point"),
                RowValues.longValue(row, "recommended_units"),
                RowValues.bigDecimal(row, "order_value"),
                RowValues.string(row, "reason"),
                ProposalStatus.valueOf(RowValues.string(row, "status")),
                (int) RowValues.longValue(row, "version"),
                RowValues.instant(row, "decided_at"),
                RowValues.string(row, "decided_by"),
                RowValues.string(row, "decision_note"),
                RowValues.string(row, "order_reference"));
    }

    private ProposalDecision toDecision(Map<String, Object> row) {
        return new ProposalDecision(
                RowValues.string(row, "decision_id"),
                RowValues.string(row, "run_id"),
                RowValues.string(row, "store_id"),
                RowValues.string(row, "product_id"),
                ProposalStatus.valueOf(RowValues.string(row, "from_status")),
                ProposalStatus.valueOf(RowValues.string(row, "to_status")),
                (int) RowValues.longValue(row, "expected_version"),
                RowValues.string(row, "decided_by"),
                RowValues.string(row, "note"),
                RowValues.string(row, "order_reference"),
                RowValues.instant(row, "decided_at"));
    }


    private long execute(String sqlName, Map<String, QueryParameterValue> parameters) {
        return dmlGateway.execute(sqlCatalog.sql(sqlName), parameters, labels("replenishment-run"));
    }

    private static Map<String, String> labels(String surface) {
        return com.springtime.bigquery.observability.JobLabels.of(surface);
    }
}
