package com.springtime.bigquery.retail.replenishment.dto;

import com.springtime.bigquery.util.Notes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Approve/reject request with an optimistic-concurrency token. */
public record ProposalDecisionRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @Size(max = Notes.MAX_NOTE) String note
) {
}
