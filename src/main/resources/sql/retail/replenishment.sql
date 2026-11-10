WITH {{include:latest_inventory}}, {{include:demand_window}}, {{include:replenishment_candidates}},
{{include:recommendation_formula}}
SELECT
  store_id,
  store_name,
  product_id,
  sku,
  product_name,
  category,
  supplier_id,
  supplier_name,
  available_units,
  avg_daily_units,
  days_of_supply,
  lead_time_days,
  effective_reorder_point,
  recommended_units,
  CAST(CAST(recommended_units AS NUMERIC) * unit_cost AS STRING) AS order_value
FROM final
WHERE avg_daily_units > 0
  AND available_units <= effective_reorder_point
  AND recommended_units > 0
ORDER BY days_of_supply ASC, CAST(recommended_units AS NUMERIC) * unit_cost DESC
LIMIT @row_limit;
