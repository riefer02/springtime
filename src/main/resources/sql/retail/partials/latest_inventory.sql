latest_snapshot AS (
  SELECT
    store_id,
    product_id,
    snapshot_date,
    COALESCE(on_hand_units, 0) AS on_hand_units,
    COALESCE(in_transit_units, 0) AS in_transit_units,
    COALESCE(reserved_units, 0) AS reserved_units,
    COALESCE(safety_stock_units, 0) AS safety_stock_units,
    COALESCE(reorder_point_units, 0) AS reorder_point_units,
    COALESCE(unit_cost, 0) AS unit_cost
  FROM `__PROJECT__.__DATASET__.inventory_snapshots`
  WHERE snapshot_date <= @as_of_date
    AND store_id = @store_id
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY store_id, product_id
    ORDER BY snapshot_date DESC
  ) = 1
),
latest_ledger AS (
  SELECT
    store_id,
    product_id,
    on_hand_units
  FROM `__PROJECT__.__DATASET__.inventory_on_hand_daily`
  WHERE business_date <= @as_of_date
    AND store_id = @store_id
  QUALIFY ROW_NUMBER() OVER (
    PARTITION BY store_id, product_id
    ORDER BY business_date DESC
  ) = 1
),
latest AS (
  SELECT
    COALESCE(l.store_id, s.store_id) AS store_id,
    COALESCE(l.product_id, s.product_id) AS product_id,
    s.snapshot_date,
    COALESCE(l.on_hand_units, s.on_hand_units, 0) AS on_hand_units,
    COALESCE(s.in_transit_units, 0) AS in_transit_units,
    COALESCE(s.reserved_units, 0) AS reserved_units,
    COALESCE(s.safety_stock_units, 0) AS safety_stock_units,
    COALESCE(s.reorder_point_units, 0) AS reorder_point_units,
    COALESCE(s.unit_cost, 0) AS unit_cost
  FROM latest_ledger l
  FULL OUTER JOIN latest_snapshot s USING (store_id, product_id)
)
