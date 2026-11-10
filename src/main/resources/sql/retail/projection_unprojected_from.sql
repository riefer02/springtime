SELECT CAST(MIN(m.business_date) AS STRING) AS min_business_date
FROM `__PROJECT__.__DATASET__.inventory_movements` m
WHERE m.ingested_at > (
  SELECT COALESCE(
    MAX(p.projected_at),
    TIMESTAMP('1970-01-01 00:00:00+00')
  )
  FROM `__PROJECT__.__DATASET__.inventory_on_hand_projection` p
);
