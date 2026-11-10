targeted AS (
  SELECT
    *,
    CAST(CEIL(GREATEST(0, @target_cover_days * avg_daily_units - available_units)) AS INT64) AS target_units
  FROM enriched
),
cased AS (
  SELECT
    *,
    CAST(
      CEIL(SAFE_DIVIDE(target_units, COALESCE(NULLIF(case_pack_size, 0), 1)))
        * COALESCE(NULLIF(case_pack_size, 0), 1)
      AS INT64
    ) AS case_units
  FROM targeted
),
final AS (
  SELECT
    *,
    CASE
      WHEN case_units > 0 AND case_units < min_order_qty THEN min_order_qty
      ELSE case_units
    END AS recommended_units
  FROM cased
)
