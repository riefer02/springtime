ALTER TABLE purchase_orders
  ADD CONSTRAINT ck_purchase_orders_status
  CHECK (status IN ('DRAFT', 'SUBMITTED', 'CANCELLED'));

ALTER TABLE purchase_order_lines
  ADD CONSTRAINT ck_purchase_order_lines_units
  CHECK (ordered_units > 0);
