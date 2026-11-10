SELECT
  event_id,
  movement_type,
  FORMAT_TIMESTAMP('%Y-%m-%dT%H:%M:%E*SZ', event_time) AS occurred_at,
  CAST(business_date AS STRING) AS business_date,
  store_id,
  product_id,
  quantity_delta,
  CAST(unit_cost AS STRING) AS unit_cost,
  currency,
  source_system,
  source_document_id,
  schema_version
FROM `__PROJECT__.__DATASET__.inventory_movements`
WHERE business_date BETWEEN @from_date AND @to_date
  AND (@store_id = '' OR store_id = @store_id)
  AND (@product_id = '' OR product_id = @product_id)
QUALIFY ROW_NUMBER() OVER (
  PARTITION BY event_id
  ORDER BY ingested_at, event_time
) = 1
ORDER BY event_time DESC, event_id
LIMIT @row_limit OFFSET @row_offset;
