MERGE `__PROJECT__.__DATASET__.replenishment_proposals` AS target
USING (
  WITH {{include:latest_inventory}}, {{include:demand_window}}, {{include:replenishment_candidates}},
  {{include:recommendation_formula}}
  SELECT
    @run_id AS run_id,
    @as_of_date AS as_of_date,
    store_id,
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
    CAST(recommended_units AS NUMERIC) * unit_cost AS order_value,
    CASE
      WHEN available_units <= 0 THEN 'STOCKOUT'
      WHEN days_of_supply IS NOT NULL AND days_of_supply < lead_time_days THEN 'BELOW_LEAD_TIME'
      WHEN available_units <= safety_stock_units THEN 'BELOW_SAFETY_STOCK'
      ELSE 'BELOW_REORDER_POINT'
    END AS reason,
    CURRENT_TIMESTAMP() AS created_at
  FROM final
  WHERE avg_daily_units > 0
    AND available_units <= effective_reorder_point
    AND recommended_units > 0
  ORDER BY days_of_supply ASC, CAST(recommended_units AS NUMERIC) * unit_cost DESC
  LIMIT @row_limit
) AS source
ON target.run_id = source.run_id
  AND target.store_id = source.store_id
  AND target.product_id = source.product_id
WHEN MATCHED AND target.status = 'PROPOSED' THEN
  UPDATE SET
    as_of_date = source.as_of_date,
    sku = source.sku,
    product_name = source.product_name,
    category = source.category,
    supplier_id = source.supplier_id,
    supplier_name = source.supplier_name,
    available_units = source.available_units,
    avg_daily_units = source.avg_daily_units,
    days_of_supply = source.days_of_supply,
    lead_time_days = source.lead_time_days,
    effective_reorder_point = source.effective_reorder_point,
    recommended_units = source.recommended_units,
    order_value = source.order_value,
    reason = source.reason,
    created_at = source.created_at
WHEN NOT MATCHED THEN
  INSERT (run_id, as_of_date, store_id, product_id, sku, product_name, category,
          supplier_id, supplier_name, available_units, avg_daily_units,
          days_of_supply, lead_time_days, effective_reorder_point,
          recommended_units, order_value, reason, status, version, created_at)
  VALUES (source.run_id, source.as_of_date, source.store_id, source.product_id,
          source.sku, source.product_name, source.category, source.supplier_id,
          source.supplier_name, source.available_units, source.avg_daily_units,
          source.days_of_supply, source.lead_time_days, source.effective_reorder_point,
          source.recommended_units, source.order_value, source.reason,
          'PROPOSED', 0, source.created_at);
