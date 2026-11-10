enriched AS (
  SELECT
    l.store_id,
    st.store_name,
    l.product_id,
    p.sku,
    p.product_name,
    p.category,
    p.supplier_id,
    sup.supplier_name,
    l.on_hand_units,
    l.in_transit_units,
    l.reserved_units,
    l.on_hand_units + l.in_transit_units - l.reserved_units AS available_units,
    l.safety_stock_units,
    l.reorder_point_units,
    COALESCE(COALESCE(p.lead_time_days, sup.lead_time_days), 0) AS lead_time_days,
    COALESCE(SAFE_DIVIDE(d.units_sold, @demand_days), 0) AS avg_daily_units,
    SAFE_DIVIDE(
      l.on_hand_units + l.in_transit_units - l.reserved_units,
      SAFE_DIVIDE(d.units_sold, @demand_days)
    ) AS days_of_supply,
    GREATEST(
      l.reorder_point_units,
      CAST(
        CEIL(COALESCE(SAFE_DIVIDE(d.units_sold, @demand_days), 0)
             * COALESCE(COALESCE(p.lead_time_days, sup.lead_time_days), 0)) AS INT64
      ) + l.safety_stock_units
    ) AS effective_reorder_point,
    p.case_pack_size,
    p.min_order_qty,
    l.unit_cost
  FROM latest l
  JOIN `__PROJECT__.__DATASET__.stores` st USING (store_id)
  JOIN `__PROJECT__.__DATASET__.products` p USING (product_id)
  JOIN `__PROJECT__.__DATASET__.suppliers` sup USING (supplier_id)
  LEFT JOIN demand d USING (store_id, product_id)
  WHERE p.discontinued_date IS NULL
)
