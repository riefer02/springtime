package com.springtime.bigquery.control.supplier;

import java.util.Optional;

/**
 * Control-plane port for reading the purchase order a transmission should carry.
 *
 * <p>Implemented by the retail purchase store. Keeping the port here means
 * {@code control.supplier} depends on no retail package.</p>
 */
public interface SupplierPurchaseOrderReader {

    Optional<SupplierPurchaseOrder> findForTransmission(String poId);
}
