SELECT
  {{include:replenishment_proposal_columns}}
FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id
  AND product_id = @product_id
LIMIT 1;
