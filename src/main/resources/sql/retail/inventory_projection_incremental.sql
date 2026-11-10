MERGE `__PROJECT__.__DATASET__.inventory_on_hand_projection` AS target
USING (
  WITH starting AS (
    SELECT store_id, product_id, on_hand_units
    FROM (
      SELECT
        store_id,
        product_id,
        on_hand_units,
        ROW_NUMBER() OVER (
          PARTITION BY store_id, product_id
          ORDER BY business_date DESC
        ) AS row_number
      FROM `__PROJECT__.__DATASET__.inventory_on_hand_projection`
      WHERE business_date < @from_date
    )
    WHERE row_number = 1
  ),
  daily AS (
    SELECT
      store_id,
      product_id,
      business_date,
      SUM(quantity_delta) AS delta
    FROM (
      SELECT *
      FROM `__PROJECT__.__DATASET__.inventory_movements`
      WHERE business_date BETWEEN @from_date AND @to_date
      QUALIFY ROW_NUMBER() OVER (
        PARTITION BY event_id
        ORDER BY ingested_at, event_time
      ) = 1
    )
    GROUP BY store_id, product_id, business_date
  ),
  running AS (
    SELECT
      store_id,
      product_id,
      business_date,
      SUM(delta) OVER (
        PARTITION BY store_id, product_id
        ORDER BY business_date
        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
      ) AS range_delta
    FROM daily
  )
  SELECT
    r.store_id,
    r.product_id,
    r.business_date,
    COALESCE(s.on_hand_units, 0) + r.range_delta AS on_hand_units
  FROM running r
  LEFT JOIN starting s USING (store_id, product_id)
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
