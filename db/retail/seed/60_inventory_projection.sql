-- Build the demo on-hand projection from the seeded ledger.
-- The projection table is normally maintained incrementally by the app; the seed
-- clears it so repeated demo runs stay deterministic.

DELETE FROM `__PROJECT__.__DATASET__.inventory_on_hand_projection`
WHERE TRUE;

MERGE `__PROJECT__.__DATASET__.inventory_on_hand_projection` AS target
USING (
  WITH daily AS (
    SELECT
      store_id,
      product_id,
      business_date,
      SUM(quantity_delta) AS delta
    FROM `__PROJECT__.__DATASET__.inventory_movements_deduped`
    GROUP BY store_id, product_id, business_date
  )
  SELECT
    store_id,
    product_id,
    business_date,
    SUM(delta) OVER (
      PARTITION BY store_id, product_id
      ORDER BY business_date
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS on_hand_units
  FROM daily
) AS source
ON target.store_id = source.store_id
  AND target.product_id = source.product_id
  AND target.business_date = source.business_date
WHEN MATCHED THEN
  UPDATE SET on_hand_units = source.on_hand_units, projected_at = CURRENT_TIMESTAMP()
WHEN NOT MATCHED THEN
  INSERT (business_date, store_id, product_id, on_hand_units, projected_at)
  VALUES (source.business_date, source.store_id, source.product_id,
          source.on_hand_units, CURRENT_TIMESTAMP());
