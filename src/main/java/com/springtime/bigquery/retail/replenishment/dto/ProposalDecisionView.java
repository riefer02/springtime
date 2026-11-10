package com.springtime.bigquery.retail.replenishment.dto;

import java.time.Instant;

/** API view of one append-only proposal decision. */
public record ProposalDecisionView(
        String decisionId,
        String runId,
        String storeId,
        String productId,
        String fromStatus,
        String toStatus,
        int expectedVersion,
        String decidedBy,
        String note,
        String orderReference,
        Instant decidedAt
) {
}
