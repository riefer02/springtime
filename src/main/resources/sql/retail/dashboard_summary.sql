WITH {{include:latest_inventory}},
inv AS (
  SELECT
    IFNULL(SUM(on_hand_units), 0) AS on_hand_units,
    IFNULL(SUM(CAST(on_hand_units AS NUMERIC) * IFNULL(unit_cost, 0)), 0) AS inventory_value,
    COUNTIF(on_hand_units <= 0) AS stockout_skus,
    COUNTIF(on_hand_units + in_transit_units - reserved_units <= reorder_point_units) AS low_stock_skus
  FROM latest
),
sales AS (
  SELECT
    IFNULL(SUM(units_sold), 0) AS units_28d,
    IFNULL(SUM(net_sales), 0) AS net_sales_28d
  FROM `__PROJECT__.__DATASET__.sales`
  WHERE sale_date BETWEEN DATE_SUB(@as_of_date, INTERVAL 28 DAY)
                      AND DATE_SUB(@as_of_date, INTERVAL 1 DAY)
    AND store_id = @store_id
),
late_ship AS (
  SELECT COUNT(DISTINCT shipment_id) AS open_late_shipments
  FROM `__PROJECT__.__DATASET__.shipments`
  WHERE expected_arrival_date < @as_of_date
    AND actual_arrival_date IS NULL
    AND store_id = @store_id
),
ship AS (
  SELECT COUNTIF(actual_arrival_date > expected_arrival_date) AS delivered_late_lines
  FROM `__PROJECT__.__DATASET__.shipments`
  WHERE expected_arrival_date BETWEEN DATE_SUB(@as_of_date, INTERVAL 30 DAY) AND @as_of_date
    AND store_id = @store_id
),
store_check AS (
  SELECT COUNT(*) AS store_exists FROM `__PROJECT__.__DATASET__.stores` WHERE store_id = @store_id
),
promo AS (
  SELECT COUNT(*) AS active_promotions
  FROM `__PROJECT__.__DATASET__.promotions`
  WHERE start_date <= @as_of_date
    AND end_date >= @as_of_date
    AND (store_id IS NULL OR store_id = @store_id)
)
SELECT
  @store_id AS store_id,
  CAST(@as_of_date AS STRING) AS as_of_date,
  inv.on_hand_units,
  CAST(inv.inventory_value AS STRING) AS inventory_value,
  inv.stockout_skus,
  inv.low_stock_skus,
  sales.units_28d,
  CAST(sales.net_sales_28d AS STRING) AS net_sales_28d,
  late_ship.open_late_shipments,
  ship.delivered_late_lines,
  promo.active_promotions,
  store_check.store_exists
FROM inv
CROSS JOIN sales
CROSS JOIN late_ship
CROSS JOIN ship
CROSS JOIN promo
CROSS JOIN store_check;
