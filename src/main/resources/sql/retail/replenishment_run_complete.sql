UPDATE `__PROJECT__.__DATASET__.replenishment_runs`
SET status = 'COMPLETED',
    completed_at = TIMESTAMP(@completed_at),
    proposal_count = @proposal_count,
    total_order_value = CAST(@total_order_value AS NUMERIC),
    error_message = NULL
WHERE run_id = @run_id
  AND status = 'RUNNING';
