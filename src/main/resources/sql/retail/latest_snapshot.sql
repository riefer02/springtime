SELECT
  CAST(MAX(snapshot_date) AS STRING) AS latest_snapshot_date
FROM `__PROJECT__.__DATASET__.inventory_snapshots`
-- Guard against a missing partition predicate; the sentinel includes all real snapshot dates.
WHERE snapshot_date >= DATE '2000-01-01';
