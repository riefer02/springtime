package com.springtime.bigquery.control.supplier;

/** Supplier adapter port. Implementations may use EDI, a supplier API, or a portal. */
public interface SupplierGateway {

    SupplierSubmission submit(SupplierTransmission transmission);
}
