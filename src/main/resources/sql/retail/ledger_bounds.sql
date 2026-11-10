SELECT
  CAST(MIN(business_date) AS STRING) AS min_date,
  CAST(MAX(business_date) AS STRING) AS max_date
FROM `__PROJECT__.__DATASET__.inventory_movements`;
