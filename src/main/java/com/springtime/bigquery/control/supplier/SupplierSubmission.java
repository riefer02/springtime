package com.springtime.bigquery.control.supplier;

import java.time.Instant;

/** Result of handing one purchase order to a supplier adapter. */
public record SupplierSubmission(String supplierReference, Instant acceptedAt) {
}
