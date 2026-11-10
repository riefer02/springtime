SELECT
  {{include:replenishment_proposal_columns}}
FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id
ORDER BY days_of_supply ASC, order_value DESC, product_id
LIMIT @row_limit OFFSET @row_offset;
