package com.springtime.bigquery.retail.purchase.store;

import com.springtime.bigquery.model.ParameterType;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.retail.purchase.application.ApprovedProposal;
import com.springtime.bigquery.retail.purchase.application.ApprovedProposalQuery;
import com.springtime.bigquery.retail.query.RetailQuery;
import com.springtime.bigquery.retail.query.RetailQueryResult;
import com.springtime.bigquery.retail.query.RetailSqlExecutor;
import com.springtime.bigquery.util.RowValues;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read adapter for approved proposals. It owns the cross-feature read and maps the replenishment
 * row into the purchase-owned {@link ApprovedProposal} contract.
 */
@Component
public class BigQueryApprovedProposalQuery implements ApprovedProposalQuery {

    private final RetailSqlExecutor executor;

    public BigQueryApprovedProposalQuery(RetailSqlExecutor executor) {
        this.executor = executor;
    }

    @Override
    public List<ApprovedProposal> approvedForPo(String runId, String storeId,
                                                String supplierId, int limit) {
        RetailQueryResult result = executor.execute(new RetailQuery(
                "replenishment_proposals_for_po",
                List.of(
                        new QueryParameter("run_id", ParameterType.STRING, runId),
                        new QueryParameter("store_id", ParameterType.STRING, storeId),
                        new QueryParameter("supplier_id", ParameterType.STRING, supplierId),
                        new QueryParameter("row_limit", ParameterType.INT64, limit)),
                limit));
        return result.rows().stream().map(BigQueryApprovedProposalQuery::toProposal).toList();
    }

    private static ApprovedProposal toProposal(java.util.Map<String, Object> row) {
        return new ApprovedProposal(
                RowValues.string(row, "product_id"),
                RowValues.string(row, "sku"),
                RowValues.string(row, "product_name"),
                RowValues.string(row, "supplier_id"),
                RowValues.string(row, "supplier_name"),
                RowValues.longValue(row, "recommended_units"),
                RowValues.bigDecimal(row, "order_value"),
                (int) RowValues.longValue(row, "version"));
    }
}
