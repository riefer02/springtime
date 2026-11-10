SELECT
  COUNT(*) AS proposal_count,
  CAST(COALESCE(SUM(order_value), 0) AS STRING) AS total_order_value
FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id;
