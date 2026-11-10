ALTER TABLE supplier_transmissions
  ADD COLUMN acked_by VARCHAR(64),
  ADD COLUMN retried_by VARCHAR(64);

ALTER TABLE supplier_transmissions
  ADD CONSTRAINT ck_supplier_transmissions_status
  CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'ACKED', 'FAILED'));

CREATE INDEX idx_supplier_transmissions_sending
  ON supplier_transmissions (updated_at)
  WHERE status = 'SENDING';
