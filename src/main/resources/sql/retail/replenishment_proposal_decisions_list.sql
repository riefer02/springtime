SELECT
  decision_id,
  run_id,
  store_id,
  product_id,
  from_status,
  to_status,
  expected_version,
  decided_by,
  note,
  order_reference,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', decided_at) AS decided_at
FROM `__PROJECT__.__DATASET__.replenishment_proposal_decisions`
WHERE run_id = @run_id
  AND product_id = @product_id
ORDER BY decided_at DESC, decision_id
LIMIT @row_limit OFFSET @row_offset;
