UPDATE `__PROJECT__.__DATASET__.replenishment_runs`
SET status = 'FAILED',
    completed_at = TIMESTAMP(@completed_at),
    error_message = @error_message
WHERE run_id = @run_id
  AND status = 'RUNNING';
