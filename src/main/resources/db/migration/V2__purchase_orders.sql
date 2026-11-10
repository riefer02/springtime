CREATE TABLE purchase_orders (
  po_id UUID PRIMARY KEY,
  po_number VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(200) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  supplier_id VARCHAR(64) NOT NULL,
  supplier_name VARCHAR(200),
  status VARCHAR(20) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  total_value NUMERIC(38, 9),
  line_count INTEGER NOT NULL,
  requested_by VARCHAR(64),
  note VARCHAR(500),
  expected_delivery_date DATE,
  supplier_reference VARCHAR(128),
  version BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  submitted_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  CONSTRAINT uq_purchase_orders_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_purchase_orders_store ON purchase_orders (store_id, created_at DESC);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders (supplier_id, created_at DESC);

CREATE TABLE purchase_order_lines (
  po_id UUID NOT NULL REFERENCES purchase_orders (po_id) ON DELETE CASCADE,
  line_number INTEGER NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  sku VARCHAR(64),
  product_name VARCHAR(200),
  supplier_id VARCHAR(64),
  ordered_units BIGINT NOT NULL,
  line_value NUMERIC(38, 9),
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (po_id, line_number)
);

CREATE TABLE purchase_order_events (
  event_id UUID PRIMARY KEY,
  po_id UUID NOT NULL REFERENCES purchase_orders (po_id) ON DELETE CASCADE,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  actor VARCHAR(64),
  note VARCHAR(500),
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_purchase_order_events_po ON purchase_order_events (po_id, occurred_at DESC);
