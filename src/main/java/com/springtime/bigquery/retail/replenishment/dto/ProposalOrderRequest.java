package com.springtime.bigquery.retail.replenishment.dto;

import com.springtime.bigquery.util.Notes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mark-approved request; orderReference links the decision to an external order. */
public record ProposalOrderRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @Size(max = Notes.MAX_NOTE) String note,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "orderReference has an unsupported format")
        String orderReference
) {
}
