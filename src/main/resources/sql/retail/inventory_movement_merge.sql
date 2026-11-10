MERGE `__PROJECT__.__DATASET__.inventory_movements` AS target
USING (
  SELECT
    @event_id AS event_id,
    @movement_type AS movement_type,
    TIMESTAMP(@event_time) AS event_time,
    @business_date AS business_date,
    CURRENT_TIMESTAMP() AS ingested_at,
    @store_id AS store_id,
    @product_id AS product_id,
    @quantity_delta AS quantity_delta,
    CAST(NULLIF(@unit_cost, '') AS NUMERIC) AS unit_cost,
    @currency AS currency,
    @source_system AS source_system,
    @source_document_id AS source_document_id,
    @schema_version AS schema_version,
    PARSE_JSON(@payload_json) AS payload
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
