package com.springtime.bigquery.control.supplier.dto;

import jakarta.validation.constraints.Pattern;

/** Optional supplier reference when acknowledging a transmission. */
public record SupplierAckRequest(
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "supplierReference has an unsupported format")
        String supplierReference
) {
}
