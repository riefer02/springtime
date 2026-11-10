package com.springtime.bigquery.retail.purchase.dto;

import com.springtime.bigquery.util.Notes;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Submit a draft purchase order. */
public record PurchaseOrderSubmitRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "supplierReference has an unsupported format")
        String supplierReference,
        @Size(max = Notes.MAX_NOTE) String note
) {
}
