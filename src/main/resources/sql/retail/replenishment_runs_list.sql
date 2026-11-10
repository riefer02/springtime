SELECT
  {{include:replenishment_run_columns}}
FROM `__PROJECT__.__DATASET__.replenishment_runs`
WHERE (@store_id = '' OR store_id = @store_id)
ORDER BY created_at DESC, run_id
LIMIT @row_limit OFFSET @row_offset;
