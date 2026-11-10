MERGE `__PROJECT__.__DATASET__.retail_event_dead_letters` AS target
USING (
  SELECT
    @event_id AS event_id,
    @event_type AS event_type,
    @schema_version AS schema_version,
    TIMESTAMP(@failed_at) AS failed_at,
    @attempts AS attempts,
    @reason AS reason,
    NULLIF(@error_type, '') AS error_type,
    @original_topic AS original_topic,
    PARSE_JSON(@envelope_json) AS envelope,
    CURRENT_TIMESTAMP() AS ingested_at
) AS source
ON target.event_id = source.event_id
WHEN MATCHED THEN
  UPDATE SET
    event_type = source.event_type,
    schema_version = source.schema_version,
    failed_at = source.failed_at,
    attempts = source.attempts,
    reason = source.reason,
    error_type = source.error_type,
    original_topic = source.original_topic,
    envelope = source.envelope,
    replayed_at = NULL,
    replay_status = 'PENDING',
    ingested_at = source.ingested_at
WHEN NOT MATCHED THEN
  INSERT (
    event_id, event_type, schema_version, failed_at, attempts, reason,
    error_type, original_topic, envelope, replay_status, ingested_at
  )
  VALUES (
    source.event_id, source.event_type, source.schema_version, source.failed_at,
    source.attempts, source.reason, source.error_type, source.original_topic,
    source.envelope, 'PENDING', source.ingested_at
  );
