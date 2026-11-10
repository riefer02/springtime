-- ============================================================================
-- Incremental on-hand projection.
--
-- The ledger remains the source of truth. This table is the maintained read
-- model for dashboards and replenishment. A scheduled/app job merges the
-- affected date range; reconciliation compares the two.
-- ============================================================================

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.inventory_on_hand_projection` (
  business_date DATE NOT NULL,
  store_id STRING NOT NULL,
  product_id STRING NOT NULL,
  on_hand_units INT64 NOT NULL,
  projected_at TIMESTAMP NOT NULL
)
PARTITION BY business_date
CLUSTER BY store_id, product_id
OPTIONS (
  description = 'Incremental on-hand projection derived from inventory_movements.'
);
