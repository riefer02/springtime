CREATE TEMP FUNCTION bucket(key STRING, buckets INT64)
RETURNS INT64
AS (MOD(MOD(FARM_FINGERPRINT(key), buckets) + buckets, buckets));

-- Suppliers
CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.suppliers` AS
SELECT
  CONCAT('SUP-', LPAD(CAST(n AS STRING), 3, '0')) AS supplier_id,
  CONCAT('Supplier ', CAST(n AS STRING)) AS supplier_name,
  'US' AS country_code,
  3 + bucket(CONCAT('sup|', n), 5) AS lead_time_days,
  CAST(1000 + bucket(CONCAT('mov|', n), 4000) AS NUMERIC) AS min_order_value,
  30 AS payment_terms_days,
  TRUE AS is_active
FROM UNNEST(GENERATE_ARRAY(1, 4)) n;

-- Distribution centers
CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.distribution_centers` AS
SELECT
  CONCAT('DC-', LPAD(CAST(n AS STRING), 3, '0')) AS dc_id,
  CONCAT('Distribution Center ', CAST(n AS STRING)) AS dc_name,
  CASE n WHEN 1 THEN 'Northeast' WHEN 2 THEN 'South' ELSE 'West' END AS region,
  CASE n WHEN 1 THEN 'Boston' WHEN 2 THEN 'Atlanta' ELSE 'Denver' END AS city,
  CASE n WHEN 1 THEN 'MA' WHEN 2 THEN 'GA' ELSE 'CO' END AS state,
  CASE n WHEN 1 THEN 'America/New_York' WHEN 2 THEN 'America/New_York' ELSE 'America/Denver' END AS time_zone,
  CAST(40.0 + n AS FLOAT64) AS latitude,
  CAST(-75.0 + n AS FLOAT64) AS longitude,
  TRUE AS is_active
FROM UNNEST(GENERATE_ARRAY(1, 3)) n;

-- Stores
CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.stores` AS
SELECT
  CONCAT('ST-', LPAD(CAST(n AS STRING), 3, '0')) AS store_id,
  CONCAT('Store ', LPAD(CAST(n AS STRING), 2, '0')) AS store_name,
  CASE MOD(n - 1, 4)
    WHEN 0 THEN 'Northeast' WHEN 1 THEN 'South' WHEN 2 THEN 'Midwest' ELSE 'West'
  END AS region,
  CASE MOD(n - 1, 4)
    WHEN 0 THEN 'Boston' WHEN 1 THEN 'Atlanta' WHEN 2 THEN 'Chicago' ELSE 'Denver'
  END AS city,
  CASE MOD(n - 1, 4)
    WHEN 0 THEN 'MA' WHEN 1 THEN 'GA' WHEN 2 THEN 'IL' ELSE 'CO'
  END AS state,
  CASE MOD(n - 1, 4)
    WHEN 0 THEN '02116' WHEN 1 THEN '30303' WHEN 2 THEN '60601' ELSE '80202'
  END AS postal_code,
  CASE MOD(n - 1, 4)
    WHEN 0 THEN 'America/New_York' WHEN 1 THEN 'America/New_York'
    WHEN 2 THEN 'America/Chicago' ELSE 'America/Denver'
  END AS time_zone,
  CONCAT('DC-', LPAD(CAST(1 + MOD(n - 1, 3) AS STRING), 3, '0')) AS dc_id,
  CASE MOD(n, 3) WHEN 0 THEN 'URBAN' WHEN 1 THEN 'SUBURBAN' ELSE 'FLAGSHIP' END AS store_format,
  DATE_SUB(DATE '2026-11-09', INTERVAL 300 + n * 37 DAY) AS opened_date,
  TRUE AS is_active
FROM UNNEST(GENERATE_ARRAY(1, 10)) n;

-- Products / SKUs
CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.products` AS
WITH base AS (
  SELECT
    n,
    CASE MOD(n, 6)
      WHEN 0 THEN 'Beverages' WHEN 1 THEN 'Dairy' WHEN 2 THEN 'Bakery'
      WHEN 3 THEN 'Pantry' WHEN 4 THEN 'Household' ELSE 'Personal Care'
    END AS category,
    CASE MOD(n, 6)
      WHEN 0 THEN 'Coffee' WHEN 1 THEN 'Milk' WHEN 2 THEN 'Bread'
      WHEN 3 THEN 'Pasta' WHEN 4 THEN 'Cleaning' ELSE 'Soap'
    END AS subcategory,
    MOD(n, 6) IN (1, 2) AS is_perishable,
    1 + bucket(CONCAT('sup|', n), 4) AS supplier_no,
    ROUND(CAST(200 + bucket(CONCAT('cost|', n), 1800) AS NUMERIC) / 100, 2) AS unit_cost,
    CAST(130 + bucket(CONCAT('margin|', n), 50) AS NUMERIC) / 100 AS margin_factor,
    CASE MOD(n, 4) WHEN 0 THEN 1 WHEN 1 THEN 6 WHEN 2 THEN 12 ELSE 24 END AS case_pack_size,
    2 + bucket(CONCAT('lead|', n), 5) AS lead_time_days
  FROM UNNEST(GENERATE_ARRAY(1, 24)) n
)
SELECT
  CONCAT('SKU-', LPAD(CAST(n AS STRING), 4, '0')) AS product_id,
  CONCAT('SKU-', LPAD(CAST(n AS STRING), 4, '0')) AS sku,
  CONCAT(category, ' ', subcategory, ' ', CAST(n AS STRING)) AS product_name,
  category,
  subcategory,
  CONCAT('Brand ', CAST(1 + MOD(n - 1, 6) AS STRING)) AS brand,
  CONCAT('SUP-', LPAD(CAST(supplier_no AS STRING), 3, '0')) AS supplier_id,
  unit_cost,
  ROUND(unit_cost * margin_factor, 2) AS unit_retail_price,
  case_pack_size,
  case_pack_size * 2 AS min_order_qty,
  lead_time_days,
  IF(is_perishable, 7 + bucket(CONCAT('shelf|', n), 21), 180 + bucket(CONCAT('shelf|', n), 365)) AS shelf_life_days,
  is_perishable,
  DATE_SUB(DATE '2026-11-09', INTERVAL 400 + bucket(CONCAT('launch|', n), 500) DAY) AS launch_date,
  CAST(NULL AS DATE) AS discontinued_date
FROM base;
