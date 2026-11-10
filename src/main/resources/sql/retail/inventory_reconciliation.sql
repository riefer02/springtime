WITH snapshot AS (
  SELECT
    store_id,
    product_id,
    snapshot_date,
    on_hand_units
  FROM `__PROJECT__.__DATASET__.inventory_snapshots`
  WHERE snapshot_date <= @as_of_date
    AND (@store_id = '' OR store_id = @store_id)
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY store_id, product_id
    ORDER BY snapshot_date DESC
  ) = 1
),
ledger AS (
  SELECT
    m.store_id,
    m.product_id,
    SUM(m.quantity_delta) AS ledger_units
  FROM (
    SELECT *
    FROM `__PROJECT__.__DATASET__.inventory_movements`
    WHERE business_date <= @as_of_date
    QUALIFY ROW_NUMBER() OVER (
      PARTITION BY event_id
      ORDER BY ingested_at, event_time
    ) = 1
  ) m
  LEFT JOIN snapshot s USING (store_id, product_id)
  WHERE m.business_date <= COALESCE(s.snapshot_date, @as_of_date)
    AND (@store_id = '' OR m.store_id = @store_id)
  GROUP BY m.store_id, m.product_id
)
SELECT
  COALESCE(l.store_id, s.store_id) AS store_id,
  COALESCE(l.product_id, s.product_id) AS product_id,
  COALESCE(l.ledger_units, 0) AS ledger_units,
  COALESCE(s.on_hand_units, 0) AS snapshot_units,
  COALESCE(l.ledger_units, 0) - COALESCE(s.on_hand_units, 0) AS drift_units
FROM ledger l
FULL OUTER JOIN snapshot s
  ON l.store_id = s.store_id AND l.product_id = s.product_id
WHERE ABS(COALESCE(l.ledger_units, 0) - COALESCE(s.on_hand_units, 0)) > @tolerance
ORDER BY ABS(drift_units) DESC, store_id, product_id
LIMIT @row_limit;
