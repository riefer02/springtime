CREATE TABLE control_run_leases (
  run_id VARCHAR(64) PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  acquired_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_control_run_leases_expiry
  ON control_run_leases (expires_at);
