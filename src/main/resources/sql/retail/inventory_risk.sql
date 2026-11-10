WITH {{include:latest_inventory}}, {{include:demand_window}},
enriched AS (
  SELECT
    l.store_id,
    st.store_name,
    st.region,
    l.product_id,
    p.sku,
    p.product_name,
    p.category,
    l.snapshot_date,
    l.on_hand_units,
    l.in_transit_units,
    l.reserved_units,
    l.on_hand_units + l.in_transit_units - l.reserved_units AS available_units,
    l.safety_stock_units,
    l.reorder_point_units,
    COALESCE(SAFE_DIVIDE(d.units_sold, @demand_days), 0) AS avg_daily_units,
    l.unit_cost,
    CAST(l.on_hand_units AS NUMERIC) * l.unit_cost AS inventory_value,
    COALESCE(COALESCE(p.lead_time_days, sup.lead_time_days), 0) AS lead_time_days
  FROM latest l
  JOIN `__PROJECT__.__DATASET__.stores` st USING (store_id)
  JOIN `__PROJECT__.__DATASET__.products` p USING (product_id)
  JOIN `__PROJECT__.__DATASET__.suppliers` sup USING (supplier_id)
  LEFT JOIN demand d USING (store_id, product_id)
  WHERE (@category = '' OR p.category = @category)
),
metrics AS (
  SELECT
    *,
    SAFE_DIVIDE(available_units, avg_daily_units) AS days_of_supply
  FROM enriched
),
risk AS (
  SELECT
    *,
    CASE
      WHEN on_hand_units <= 0 THEN 'STOCKOUT'
      WHEN avg_daily_units = 0 THEN 'NO_DEMAND'
      WHEN available_units <= safety_stock_units OR days_of_supply < lead_time_days THEN 'CRITICAL'
      WHEN available_units <= reorder_point_units THEN 'WATCH'
      ELSE 'HEALTHY'
    END AS risk_level,
    IF(
      days_of_supply IS NULL,
      CAST(NULL AS DATE),
      DATE_ADD(@as_of_date, INTERVAL GREATEST(LEAST(CAST(FLOOR(days_of_supply) AS INT64), 36500), -36500) DAY)
    ) AS projected_stockout_date
  FROM metrics
),
filtered AS (
  SELECT *
  FROM risk
  WHERE (@risk_level = '' OR risk_level = @risk_level)
)
SELECT
  store_id,
  store_name,
  region,
  product_id,
  sku,
  product_name,
  category,
  CAST(snapshot_date AS STRING) AS snapshot_date,
  on_hand_units,
  in_transit_units,
  reserved_units,
  available_units,
  safety_stock_units,
  reorder_point_units,
  avg_daily_units,
  days_of_supply,
  lead_time_days,
  risk_level,
  CAST(projected_stockout_date AS STRING) AS projected_stockout_date,
  CAST(unit_cost AS STRING) AS unit_cost,
  CAST(inventory_value AS STRING) AS inventory_value
FROM filtered
ORDER BY
  CASE risk_level
    WHEN 'STOCKOUT' THEN 1
    WHEN 'CRITICAL' THEN 2
    WHEN 'WATCH' THEN 3
    WHEN 'NO_DEMAND' THEN 4
    ELSE 5
  END,
  days_of_supply ASC,
  sku ASC
LIMIT @row_limit;
