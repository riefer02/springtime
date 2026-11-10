SELECT
  store_id,
  store_name,
  region,
  city,
  state,
  time_zone,
  dc_id,
  store_format
FROM `__PROJECT__.__DATASET__.stores`
WHERE is_active
ORDER BY store_id
LIMIT @row_limit OFFSET @row_offset;
