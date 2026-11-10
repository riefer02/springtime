CREATE TABLE supplier_transmissions (
  transmission_id UUID PRIMARY KEY,
  po_id VARCHAR(64) NOT NULL,
  supplier_id VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  supplier_reference VARCHAR(128),
  last_error VARCHAR(500),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  sent_at TIMESTAMPTZ,
  acked_at TIMESTAMPTZ,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uq_supplier_transmissions_po UNIQUE (po_id)
);

CREATE INDEX idx_supplier_transmissions_due
  ON supplier_transmissions (status, next_attempt_at);
