UPDATE `__PROJECT__.__DATASET__.retail_event_dead_letters`
SET replayed_at = TIMESTAMP(@replayed_at),
    replay_status = 'REPLAYED'
WHERE event_id = @event_id;
