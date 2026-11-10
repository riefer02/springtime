CREATE TEMP FUNCTION bucket(key STRING, buckets INT64)
RETURNS INT64
AS (MOD(MOD(FARM_FINGERPRINT(key), buckets) + buckets, buckets));

CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.inventory_snapshots`
PARTITION BY snapshot_date
CLUSTER BY store_id, product_id
OPTIONS (
  description = 'Synthetic daily retail inventory snapshots; one row per store/product/date.',
  require_partition_filter = TRUE
)
AS
WITH days AS (
  SELECT d AS snapshot_date
  FROM UNNEST(GENERATE_DATE_ARRAY(DATE_SUB(DATE '2026-11-09', INTERVAL 89 DAY), DATE '2026-11-09')) d
),
grid AS (
  SELECT d.snapshot_date, s.store_id, s.time_zone, p.product_id, p.unit_cost, p.unit_retail_price
  FROM days d
  CROSS JOIN `__PROJECT__.__DATASET__.stores` s
  CROSS JOIN `__PROJECT__.__DATASET__.products` p
)
SELECT
  snapshot_date,
  TIMESTAMP(DATETIME(snapshot_date, TIME(23, 59, 59)), time_zone) AS snapshot_ts,
  store_id,
  product_id,
  CAST(bucket(CONCAT('oh|', store_id, '|', product_id, '|', CAST(snapshot_date AS STRING)), 120) AS INT64) AS on_hand_units,
  CAST(bucket(CONCAT('it|', store_id, '|', product_id, '|', CAST(snapshot_date AS STRING)), 30) AS INT64) AS in_transit_units,
  CAST(bucket(CONCAT('res|', store_id, '|', product_id, '|', CAST(snapshot_date AS STRING)), 10) AS INT64) AS reserved_units,
  CAST(10 + bucket(CONCAT('ss|', store_id, '|', product_id, '|', CAST(snapshot_date AS STRING)), 25) AS INT64) AS safety_stock_units,
  CAST(30 + bucket(CONCAT('rop|', store_id, '|', product_id, '|', CAST(snapshot_date AS STRING)), 40) AS INT64) AS reorder_point_units,
  unit_cost,
  unit_retail_price
FROM grid;
