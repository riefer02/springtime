SELECT
  {{include:replenishment_run_columns}}
FROM `__PROJECT__.__DATASET__.replenishment_runs`
WHERE run_id = @run_id
LIMIT 1;
