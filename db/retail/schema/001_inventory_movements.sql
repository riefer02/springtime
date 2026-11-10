-- ============================================================================
-- Inventory movement ledger
--
-- On-hand is a projection over these rows. Do not update a balance in place.
-- event_id is the idempotency key; the sink uses MERGE on it.
-- ============================================================================

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.inventory_movements` (
  event_id STRING NOT NULL,
  movement_type STRING NOT NULL,
  event_time TIMESTAMP NOT NULL,
  business_date DATE NOT NULL,
  ingested_at TIMESTAMP NOT NULL,
  store_id STRING NOT NULL,
  product_id STRING NOT NULL,
  quantity_delta INT64 NOT NULL,
  unit_cost NUMERIC,
  currency STRING NOT NULL,
  source_system STRING NOT NULL,
  source_document_id STRING NOT NULL,
  schema_version STRING NOT NULL,
  payload JSON
)
PARTITION BY business_date
CLUSTER BY event_id, store_id, product_id
OPTIONS (
  description = 'Append-only inventory movement ledger. event_id is the idempotency key.'
);
