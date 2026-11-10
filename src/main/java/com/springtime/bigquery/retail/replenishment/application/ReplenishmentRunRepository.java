package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunSummary;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Storage boundary for replenishment runs and proposals. */
public interface ReplenishmentRunRepository {

    void begin(ReplenishmentRun run);

    void clearProposals(String runId);

    boolean hasDecisions(String runId);

    void mergeProposals(ReplenishmentRun run, int rowLimit);

    ReplenishmentRunSummary summary(String runId);

    void complete(String runId, ReplenishmentRunSummary summary, Instant completedAt);

    void fail(String runId, String errorMessage, Instant completedAt);

    Optional<ReplenishmentRun> find(String runId);

    List<ReplenishmentRun> list(String storeId, int limit, int offset);

    List<ReplenishmentProposal> proposals(String runId, int limit, int offset);

    Optional<ReplenishmentProposal> findProposal(String runId, String productId);

    /** Applies the proposal transition and its audit record in one transaction. */
    void decideProposal(ProposalDecision decision);

    List<ProposalDecision> decisions(String runId, String productId, int limit, int offset);
}
