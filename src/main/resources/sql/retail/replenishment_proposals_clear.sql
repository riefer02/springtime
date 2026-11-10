DELETE FROM `__PROJECT__.__DATASET__.replenishment_proposals`
WHERE run_id = @run_id
  AND status = 'PROPOSED';
