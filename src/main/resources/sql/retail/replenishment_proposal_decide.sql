BEGIN TRANSACTION;

ASSERT (
  (SELECT COUNT(*) FROM `__PROJECT__.__DATASET__.replenishment_runs`
   WHERE run_id = @run_id AND status = 'COMPLETED') = 1
) AS 'run is not accepting decisions';

UPDATE `__PROJECT__.__DATASET__.replenishment_proposals`
SET status = @to_status,
    version = version + 1,
    decided_at = TIMESTAMP(@decided_at),
    decided_by = @decided_by,
    decision_note = NULLIF(@decision_note, ''),
    order_reference = NULLIF(@order_reference, '')
WHERE run_id = @run_id
  AND product_id = @product_id
  AND version = @expected_version
  AND status = @from_status;

ASSERT (@@row_count = 1) AS 'proposal transition lost the optimistic version race';

INSERT INTO `__PROJECT__.__DATASET__.replenishment_proposal_decisions` (
  decision_id, run_id, store_id, product_id, from_status, to_status,
  expected_version, decided_by, note, order_reference, decided_at
) VALUES (
  @decision_id, @run_id, @store_id, @product_id, @from_status, @to_status,
  @expected_version, @decided_by, NULLIF(@note, ''), NULLIF(@order_reference, ''),
  TIMESTAMP(@decided_at)
);

COMMIT TRANSACTION;
