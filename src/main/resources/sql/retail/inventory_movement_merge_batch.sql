MERGE `__PROJECT__.__DATASET__.inventory_movements` AS target
USING (
  SELECT
    @event_ids[OFFSET(i)] AS event_id,
    @movement_types[OFFSET(i)] AS movement_type,
    TIMESTAMP(@event_times[OFFSET(i)]) AS event_time,
    DATE(@business_dates[OFFSET(i)]) AS business_date,
    CURRENT_TIMESTAMP() AS ingested_at,
    @store_ids[OFFSET(i)] AS store_id,
    @product_ids[OFFSET(i)] AS product_id,
    @quantity_deltas[OFFSET(i)] AS quantity_delta,
    CAST(NULLIF(@unit_costs[OFFSET(i)], '') AS NUMERIC) AS unit_cost,
    @currencies[OFFSET(i)] AS currency,
    @source_systems[OFFSET(i)] AS source_system,
    @source_document_ids[OFFSET(i)] AS source_document_id,
    @schema_versions[OFFSET(i)] AS schema_version,
    PARSE_JSON(@payload_jsons[OFFSET(i)]) AS payload
  FROM UNNEST(GENERATE_ARRAY(0, ARRAY_LENGTH(@event_ids) - 1)) AS i
  QUALIFY ROW_NUMBER() OVER (PARTITION BY @event_ids[OFFSET(i)] ORDER BY i) = 1
) AS source
ON target.event_id = source.event_id
  AND target.business_date = source.business_date
WHEN NOT MATCHED THEN
  INSERT (
    event_id, movement_type, event_time, business_date, ingested_at,
    store_id, product_id, quantity_delta, unit_cost, currency,
    source_system, source_document_id, schema_version, payload
  )
  VALUES (
    source.event_id, source.movement_type, source.event_time, source.business_date,
    source.ingested_at, source.store_id, source.product_id, source.quantity_delta,
    source.unit_cost, source.currency, source.source_system, source.source_document_id,
    source.schema_version, source.payload
  );
