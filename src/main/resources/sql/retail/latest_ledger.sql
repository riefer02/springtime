SELECT
  CAST(MAX(business_date) AS STRING) AS latest_ledger_date
FROM `__PROJECT__.__DATASET__.inventory_movements`;
