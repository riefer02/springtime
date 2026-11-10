CREATE TEMP FUNCTION bucket(key STRING, buckets INT64)
RETURNS INT64
AS (MOD(MOD(FARM_FINGERPRINT(key), buckets) + buckets, buckets));

CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.sales`
PARTITION BY sale_date
CLUSTER BY store_id, product_id
OPTIONS (
  description = 'Synthetic retail sales lines; one row per sale line.',
  require_partition_filter = TRUE
)
AS
WITH lines AS (
  SELECT
    n,
    DATE_SUB(DATE '2026-11-09', INTERVAL bucket(CONCAT('day|', n), 180) DAY) AS sale_date,
    CONCAT('ST-', LPAD(CAST(1 + bucket(CONCAT('store|', n), 10) AS STRING), 3, '0')) AS store_id,
    CONCAT('SKU-', LPAD(CAST(1 + bucket(CONCAT('product|', n), 24) AS STRING), 4, '0')) AS product_id,
    bucket(CONCAT('qty|', n), 5) AS qty_bucket,
    bucket(CONCAT('channel|', n), 100) AS channel_bucket
  FROM UNNEST(GENERATE_ARRAY(1, 60000)) n
)
SELECT
  CONCAT('SALE-', LPAD(CAST(n AS STRING), 8, '0')) AS sale_id,
  sale_date,
  TIMESTAMP(DATETIME(sale_date, TIME(8 + bucket(CONCAT('hour|', n), 12), 0, 0)), st.time_zone) AS sale_ts,
  store_id,
  product_id,
  CAST(NULL AS STRING) AS promotion_id,
  IF(channel_bucket < 80, 'IN_STORE', 'ONLINE') AS channel,
  CAST(1 + qty_bucket AS INT64) AS units_sold,
  p.unit_retail_price AS unit_price,
  p.unit_cost,
  CAST(1 + qty_bucket AS NUMERIC) * p.unit_retail_price AS gross_sales,
  CAST(0 AS NUMERIC) AS discount_amount,
  CAST(1 + qty_bucket AS NUMERIC) * p.unit_retail_price AS net_sales
FROM lines
JOIN `__PROJECT__.__DATASET__.stores` st USING (store_id)
JOIN `__PROJECT__.__DATASET__.products` p USING (product_id);
