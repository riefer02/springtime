-- One live purchase order per run/store/supplier. The idempotency key is an alias; the business
-- scope is the real uniqueness boundary, so a second key cannot duplicate the same order.
-- Cancelled orders are excluded so an operator can replace a cancelled draft.
CREATE UNIQUE INDEX uq_purchase_orders_scope
  ON purchase_orders (run_id, store_id, supplier_id)
  WHERE status <> 'CANCELLED';
