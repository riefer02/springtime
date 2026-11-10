-- ============================================================================
-- Ledger dedupe view.
--
-- The sink already MERGEs on event_id; this view is a second guarantee for
-- manually loaded or replayed rows.
-- ============================================================================

CREATE OR REPLACE VIEW `__PROJECT__.__DATASET__.inventory_movements_deduped` AS
SELECT * EXCEPT (row_number)
FROM (
  SELECT
    *,
    ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingested_at, event_time) AS row_number
  FROM `__PROJECT__.__DATASET__.inventory_movements`
)
WHERE row_number = 1;
