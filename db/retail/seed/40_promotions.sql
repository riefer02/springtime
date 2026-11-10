CREATE TEMP FUNCTION bucket(key STRING, buckets INT64)
RETURNS INT64
AS (MOD(MOD(FARM_FINGERPRINT(key), buckets) + buckets, buckets));

CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.promotions` AS
WITH p AS (
  SELECT
    n,
    CONCAT('PROMO-', LPAD(CAST(n AS STRING), 3, '0')) AS promotion_id,
    CONCAT('Campaign ', CAST(n AS STRING)) AS promotion_name,
    CASE MOD(n, 3) WHEN 0 THEN 'PERCENT_OFF' WHEN 1 THEN 'FEATURE' ELSE 'DISPLAY' END AS promotion_type,
    CAST(5 + bucket(CONCAT('disc|', n), 20) AS NUMERIC) AS discount_pct,
    CASE
      WHEN n = 1 THEN DATE_SUB(DATE '2026-11-09', INTERVAL 7 DAY)
      ELSE DATE_SUB(DATE '2026-11-09', INTERVAL 30 + n * 10 DAY)
    END AS start_date,
    CASE MOD(n, 3) WHEN 0 THEN NULL ELSE CONCAT('ST-', LPAD(CAST(1 + MOD(n, 10) AS STRING), 3, '0')) END AS store_id,
    CASE MOD(n, 3) WHEN 0 THEN 'ALL' WHEN 1 THEN 'IN_STORE' ELSE 'ONLINE' END AS channel,
    TRUE AS is_active
  FROM UNNEST(GENERATE_ARRAY(1, 8)) n
)
SELECT
  promotion_id, promotion_name, promotion_type, discount_pct, start_date,
  CASE
    WHEN n = 1 THEN DATE_ADD(DATE '2026-11-09', INTERVAL 7 DAY)
    ELSE DATE_ADD(start_date, INTERVAL 7 + MOD(n, 3) * 7 DAY)
  END AS end_date,
  store_id, channel, is_active
FROM p;

CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.promotion_products` AS
SELECT
  p.promotion_id,
  CONCAT('SKU-', LPAD(CAST(1 + MOD((CAST(SUBSTR(p.promotion_id, 7) AS INT64) - 1) * 3 + k, 24) AS STRING), 4, '0')) AS product_id,
  p.discount_pct
FROM `__PROJECT__.__DATASET__.promotions` p
CROSS JOIN UNNEST(GENERATE_ARRAY(1, 3)) k;
