package com.springtime.bigquery.control.supplier;

/**
 * Control-plane port used by purchase orders to enqueue a durable transmission.
 *
 * <p>Keeping the port in {@code control.supplier} lets {@code retail.purchase} depend on the
 * control plane without a package cycle, because the control plane never imports retail.</p>
 */
public interface SupplierOutbox {

    void enqueue(String poId, String actor);
}
