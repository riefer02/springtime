SELECT
  {{include:replenishment_proposal_columns}}
FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id
  AND store_id = @store_id
  AND supplier_id = @supplier_id
  AND status = 'APPROVED'
ORDER BY product_id
LIMIT @row_limit;
