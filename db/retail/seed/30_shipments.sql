CREATE TEMP FUNCTION bucket(key STRING, buckets INT64)
RETURNS INT64
AS (MOD(MOD(FARM_FINGERPRINT(key), buckets) + buckets, buckets));

CREATE OR REPLACE TABLE `__PROJECT__.__DATASET__.shipments`
PARTITION BY expected_arrival_date
CLUSTER BY store_id, product_id, supplier_id
OPTIONS (
  description = 'Synthetic inbound shipment lines; one row per shipment line.',
  require_partition_filter = TRUE
)
AS
WITH base AS (
  SELECT
    n,
    CONCAT('SHP-', LPAD(CAST(1 + DIV(n - 1, 3) AS STRING), 6, '0')) AS shipment_id,
    CAST(1 + MOD(n - 1, 3) AS INT64) AS shipment_line_number,
    CONCAT('PO-', LPAD(CAST(1 + DIV(n - 1, 6) AS STRING), 6, '0')) AS purchase_order_id,
    CONCAT('ST-', LPAD(CAST(1 + bucket(CONCAT('store|', n), 10) AS STRING), 3, '0')) AS store_id,
    CONCAT('SKU-', LPAD(CAST(1 + bucket(CONCAT('product|', n), 24) AS STRING), 4, '0')) AS product_id,
    DATE_SUB(DATE '2026-11-09', INTERVAL bucket(CONCAT('shipday|', n), 90) DAY) AS ship_date,
    bucket(CONCAT('status|', n), 100) AS status_bucket,
    bucket(CONCAT('late|', n), 5) AS late_bucket
  FROM UNNEST(GENERATE_ARRAY(1, 2000)) n
),
joined AS (
  SELECT b.*, st.dc_id, p.supplier_id, p.unit_cost, p.lead_time_days, p.case_pack_size
  FROM base b
  JOIN `__PROJECT__.__DATASET__.stores` st USING (store_id)
  JOIN `__PROJECT__.__DATASET__.products` p USING (product_id)
),
calculated AS (
  SELECT
    *,
    DATE_ADD(ship_date, INTERVAL lead_time_days DAY) AS expected_arrival_date,
    CASE
      WHEN status_bucket < 80 THEN DATE_ADD(DATE_ADD(ship_date, INTERVAL lead_time_days DAY), INTERVAL late_bucket DAY)
      ELSE CAST(NULL AS DATE)
    END AS actual_arrival_date,
    CASE
      WHEN status_bucket < 80 AND late_bucket = 0 THEN 'DELIVERED'
      WHEN status_bucket < 80 THEN 'DELIVERED_LATE'
      ELSE 'IN_TRANSIT'
    END AS status,
    case_pack_size * (1 + bucket(CONCAT('qty|', n), 4)) AS quantity_ordered
  FROM joined
)
SELECT
  shipment_id,
  shipment_line_number,
  purchase_order_id,
  supplier_id,
  dc_id,
  store_id,
  product_id,
  ship_date,
  expected_arrival_date,
  actual_arrival_date,
  status,
  quantity_ordered,
  quantity_ordered AS quantity_shipped,
  IF(actual_arrival_date IS NOT NULL, quantity_ordered, 0) AS quantity_received,
  unit_cost,
  CASE MOD(n, 4) WHEN 0 THEN 'CARRIER_A' WHEN 1 THEN 'CARRIER_B' WHEN 2 THEN 'CARRIER_C' ELSE 'CARRIER_D' END AS carrier
FROM calculated;
