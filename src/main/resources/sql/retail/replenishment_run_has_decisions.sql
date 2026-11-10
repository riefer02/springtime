SELECT
  COUNT(*) AS decided_count
FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id
  AND status IS NOT NULL
  AND status != 'PROPOSED';
