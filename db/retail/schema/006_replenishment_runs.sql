-- ============================================================================
-- Replenishment runs and proposals.
--
-- A run is the durable decision record. Proposals are the output. Re-running
-- the same run_id replaces that run's proposals; it does not append duplicates.
-- ============================================================================

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.replenishment_runs` (
  run_id STRING NOT NULL,
  store_id STRING NOT NULL,
  as_of_date DATE NOT NULL,
  status STRING NOT NULL,
  demand_window_days INT64 NOT NULL,
  target_cover_days INT64 NOT NULL,
  requested_by STRING,
  created_at TIMESTAMP NOT NULL,
  started_at TIMESTAMP,
  completed_at TIMESTAMP,
  proposal_count INT64,
  total_order_value NUMERIC,
  error_message STRING
)
PARTITION BY as_of_date
CLUSTER BY store_id
OPTIONS (
  description = 'Replenishment run metadata and status.'
);

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.replenishment_proposals` (
  run_id STRING NOT NULL,
  as_of_date DATE NOT NULL,
  store_id STRING NOT NULL,
  product_id STRING NOT NULL,
  sku STRING,
  product_name STRING,
  category STRING,
  supplier_id STRING,
  supplier_name STRING,
  available_units INT64,
  avg_daily_units FLOAT64,
  days_of_supply FLOAT64,
  lead_time_days INT64,
  effective_reorder_point INT64,
  recommended_units INT64,
  order_value NUMERIC,
  reason STRING,
  created_at TIMESTAMP NOT NULL
)
PARTITION BY as_of_date
CLUSTER BY run_id, store_id, product_id
OPTIONS (
  description = 'Prioritized replenishment proposals produced by a run.'
);
