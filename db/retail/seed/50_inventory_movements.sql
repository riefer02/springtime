-- ============================================================================
-- Demo movement ledger derived from the synthetic inventory snapshots.
--
-- This is a seed-only bridge so reconciliation is meaningful before a real
-- POS/receiving feed exists. It is NOT how production movements should be
-- created: production writes one movement per business event.
-- ============================================================================

DELETE FROM `__PROJECT__.__DATASET__.inventory_movements`
WHERE source_system = 'retail-seed';

INSERT INTO `__PROJECT__.__DATASET__.inventory_movements` (
  event_id, movement_type, event_time, business_date, ingested_at,
  store_id, product_id, quantity_delta, unit_cost, currency,
  source_system, source_document_id, schema_version, payload
)
WITH daily AS (
  SELECT
    s.store_id,
    s.product_id,
    s.snapshot_date,
    s.on_hand_units,
    p.unit_cost,
    LAG(s.on_hand_units) OVER (
      PARTITION BY s.store_id, s.product_id
      ORDER BY s.snapshot_date
    ) AS previous_units
  FROM `__PROJECT__.__DATASET__.inventory_snapshots` s
  JOIN `__PROJECT__.__DATASET__.products` p USING (product_id)
  -- BigQuery requires a partition predicate here; the demo snapshot history ends on this date.
  WHERE s.snapshot_date <= DATE '2026-11-09'
)
SELECT
  CONCAT('seed-', store_id, '-', product_id, '-', CAST(snapshot_date AS STRING)) AS event_id,
  IF(previous_units IS NULL, 'OPENING_BALANCE',
     IF(on_hand_units >= previous_units, 'RECEIPT', 'SALE')) AS movement_type,
  TIMESTAMP(DATETIME(snapshot_date, TIME(23, 59, 59)), 'UTC') AS event_time,
  snapshot_date AS business_date,
  CURRENT_TIMESTAMP() AS ingested_at,
  store_id,
  product_id,
  IF(previous_units IS NULL, on_hand_units, on_hand_units - previous_units) AS quantity_delta,
  unit_cost,
  'USD' AS currency,
  'retail-seed' AS source_system,
  CONCAT('seed-', store_id, '-', product_id, '-', CAST(snapshot_date AS STRING)) AS source_document_id,
  '1' AS schema_version,
  PARSE_JSON('{"source":"retail-seed"}') AS payload
FROM daily
WHERE previous_units IS NULL OR on_hand_units != previous_units;
