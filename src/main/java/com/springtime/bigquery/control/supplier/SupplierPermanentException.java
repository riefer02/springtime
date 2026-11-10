package com.springtime.bigquery.control.supplier;

/** A permanent supplier/integration failure; retrying will not help. */
public class SupplierPermanentException extends RuntimeException {

    public SupplierPermanentException(String message) {
        super(message);
    }
}
