-- ============================================================================
-- Proposal decisions.
--
-- Proposals become a small state machine:
--   PROPOSED -> APPROVED | REJECTED
--   APPROVED -> ORDERED | REJECTED
-- ORDERED and REJECTED are terminal.
--
-- version is the optimistic concurrency token. Every successful transition
-- increments it and requires the caller's expectedVersion to match.
-- ============================================================================

ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS status STRING;
ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS version INT64;
ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS decided_at TIMESTAMP;
ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS decided_by STRING;
ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS decision_note STRING;
ALTER TABLE `__PROJECT__.__DATASET__.replenishment_proposals` ADD COLUMN IF NOT EXISTS order_reference STRING;

UPDATE `__PROJECT__.__DATASET__.replenishment_proposals`
SET status = 'PROPOSED', version = 0
WHERE status IS NULL OR version IS NULL;

CREATE TABLE IF NOT EXISTS `__PROJECT__.__DATASET__.replenishment_proposal_decisions` (
  decision_id STRING NOT NULL,
  run_id STRING NOT NULL,
  store_id STRING NOT NULL,
  product_id STRING NOT NULL,
  from_status STRING NOT NULL,
  to_status STRING NOT NULL,
  expected_version INT64 NOT NULL,
  decided_by STRING,
  note STRING,
  order_reference STRING,
  decided_at TIMESTAMP NOT NULL
)
PARTITION BY DATE(decided_at)
CLUSTER BY run_id, product_id
OPTIONS (
  description = 'Append-only audit trail for replenishment proposal decisions.'
);
