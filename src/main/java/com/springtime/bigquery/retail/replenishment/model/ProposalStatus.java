package com.springtime.bigquery.retail.replenishment.model;

/** Proposal lifecycle. ORDERED and REJECTED are terminal. */
public enum ProposalStatus {
    PROPOSED,
    APPROVED,
    REJECTED,
    ORDERED
}
