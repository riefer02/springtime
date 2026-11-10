demand AS (
  SELECT
    store_id,
    product_id,
    SUM(units_sold) AS units_sold
  FROM `__PROJECT__.__DATASET__.sales`
  WHERE sale_date BETWEEN DATE_SUB(@as_of_date, INTERVAL @demand_days DAY)
                      AND DATE_SUB(@as_of_date, INTERVAL 1 DAY)
    AND store_id = @store_id
  GROUP BY store_id, product_id
)
