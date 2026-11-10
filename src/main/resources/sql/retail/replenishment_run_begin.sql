MERGE `__PROJECT__.__DATASET__.replenishment_runs` AS target
USING (
  SELECT
    @run_id AS run_id,
    @store_id AS store_id,
    @as_of_date AS as_of_date,
    'RUNNING' AS status,
    @demand_window_days AS demand_window_days,
    @target_cover_days AS target_cover_days,
    @requested_by AS requested_by,
    TIMESTAMP(@created_at) AS created_at,
    TIMESTAMP(@started_at) AS started_at
) AS source
ON target.run_id = source.run_id
WHEN MATCHED THEN
  UPDATE SET
    store_id = source.store_id,
    as_of_date = source.as_of_date,
    status = source.status,
    demand_window_days = source.demand_window_days,
    target_cover_days = source.target_cover_days,
    requested_by = source.requested_by,
    created_at = source.created_at,
    started_at = source.started_at,
    completed_at = NULL,
    proposal_count = NULL,
    total_order_value = NULL,
    error_message = NULL
WHEN NOT MATCHED THEN
  INSERT (run_id, store_id, as_of_date, status, demand_window_days,
          target_cover_days, requested_by, created_at, started_at)
  VALUES (source.run_id, source.store_id, source.as_of_date, source.status,
          source.demand_window_days, source.target_cover_days, source.requested_by,
          source.created_at, source.started_at);
