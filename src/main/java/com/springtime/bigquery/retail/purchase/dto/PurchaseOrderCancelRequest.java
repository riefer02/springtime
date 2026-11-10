package com.springtime.bigquery.retail.purchase.dto;

import com.springtime.bigquery.util.Notes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cancel a draft purchase order. */
public record PurchaseOrderCancelRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @Size(max = Notes.MAX_NOTE) String note
) {
}
