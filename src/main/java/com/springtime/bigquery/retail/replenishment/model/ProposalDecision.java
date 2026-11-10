package com.springtime.bigquery.retail.replenishment.model;

import java.time.Instant;

/** One append-only proposal decision record. */
public record ProposalDecision(
        String decisionId,
        String runId,
        String storeId,
        String productId,
        ProposalStatus fromStatus,
        ProposalStatus toStatus,
        int expectedVersion,
        String decidedBy,
        String note,
        String orderReference,
        Instant decidedAt
) {
}
