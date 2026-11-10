package com.springtime.bigquery.retail.purchase.application;

import java.util.List;

/**
 * Purchase-owned port for approved proposals. The replenishment adapter maps its own model to
 * {@link ApprovedProposal}, so purchase does not depend on the replenishment package.
 */
public interface ApprovedProposalQuery {

    List<ApprovedProposal> approvedForPo(String runId, String storeId,
                                         String supplierId, int limit);
}
