SELECT
  event_id,
  event_type,
  schema_version,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', failed_at) AS failed_at,
  attempts,
  reason,
  error_type,
  original_topic,
  TO_JSON_STRING(envelope) AS envelope_json,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', replayed_at) AS replayed_at,
  replay_status
FROM `__PROJECT__.__DATASET__.retail_event_dead_letters`
WHERE event_id = @event_id
LIMIT 1;
