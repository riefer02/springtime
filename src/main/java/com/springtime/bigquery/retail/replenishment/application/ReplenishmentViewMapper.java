package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.retail.replenishment.dto.ProposalDecisionView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunView;
import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;

/** Single mapping path from domain records to API records for the replenishment slice. */
final class ReplenishmentViewMapper {

    private ReplenishmentViewMapper() {
    }

    static ReplenishmentRunView runView(ReplenishmentRun run) {
        return new ReplenishmentRunView(
                run.runId(),
                run.storeId(),
                run.asOfDate(),
                run.status().name(),
                run.demandWindowDays(),
                run.targetCoverDays(),
                run.requestedBy(),
                run.createdAt(),
                run.startedAt(),
                run.completedAt(),
                run.proposalCount(),
                run.totalOrderValue() == null ? null : run.totalOrderValue().toPlainString(),
                run.errorMessage());
    }

    static ReplenishmentProposalView proposalView(ReplenishmentProposal proposal) {
        return new ReplenishmentProposalView(
                proposal.runId(),
                proposal.asOfDate(),
                proposal.storeId(),
                proposal.productId(),
                proposal.sku(),
                proposal.productName(),
                proposal.category(),
                proposal.supplierId(),
                proposal.supplierName(),
                proposal.availableUnits(),
                proposal.avgDailyUnits(),
                proposal.daysOfSupply(),
                proposal.leadTimeDays(),
                proposal.effectiveReorderPoint(),
                proposal.recommendedUnits(),
                proposal.orderValue() == null ? null : proposal.orderValue().toPlainString(),
                proposal.reason(),
                proposal.status().name(),
                proposal.version(),
                proposal.decidedAt(),
                proposal.decidedBy(),
                proposal.decisionNote(),
                proposal.orderReference());
    }

    static ProposalDecisionView decisionView(ProposalDecision decision) {
        return new ProposalDecisionView(
                decision.decisionId(),
                decision.runId(),
                decision.storeId(),
                decision.productId(),
                decision.fromStatus().name(),
                decision.toStatus().name(),
                decision.expectedVersion(),
                decision.decidedBy(),
                decision.note(),
                decision.orderReference(),
                decision.decidedAt());
    }
}
