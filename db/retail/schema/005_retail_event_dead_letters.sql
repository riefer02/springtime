-- ============================================================================
-- Dead-letter records for replay and audit.
--
-- The Pub/Sub topic remains the transport; this table is the durable, queryable
-- record that an operator can inspect and replay.
-- ============================================================================

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.retail_event_dead_letters` (
  event_id STRING NOT NULL,
  event_type STRING,
  schema_version STRING,
  failed_at TIMESTAMP NOT NULL,
  attempts INT64 NOT NULL,
  reason STRING NOT NULL,
  error_type STRING,
  original_topic STRING NOT NULL,
  envelope JSON NOT NULL,
  replayed_at TIMESTAMP,
  replay_status STRING,
  ingested_at TIMESTAMP NOT NULL
)
PARTITION BY DATE(failed_at)
CLUSTER BY event_id
OPTIONS (
  description = 'Durable dead-letter records and replay state for retail events.'
);
