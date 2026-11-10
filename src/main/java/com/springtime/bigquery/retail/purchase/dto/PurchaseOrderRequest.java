package com.springtime.bigquery.retail.purchase.dto;

import com.springtime.bigquery.util.Notes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Create a purchase order from approved proposals for one run/store/supplier. */
public record PurchaseOrderRequest(
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}", message = "idempotencyKey has an unsupported format")
        String idempotencyKey,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "runId has an unsupported format")
        String runId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "storeId has an unsupported format")
        String storeId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "supplierId has an unsupported format")
        String supplierId,
        LocalDate expectedDeliveryDate,
        @Size(max = Notes.MAX_NOTE) String note
) {
}
