SELECT
  event_id,
  event_type,
  schema_version,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', failed_at) AS failed_at,
  attempts,
  reason,
  error_type,
  original_topic,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', replayed_at) AS replayed_at,
  replay_status
FROM `__PROJECT__.__DATASET__.retail_event_dead_letters`
ORDER BY failed_at DESC, event_id
LIMIT @row_limit OFFSET @row_offset;
