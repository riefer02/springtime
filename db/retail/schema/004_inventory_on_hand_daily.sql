-- Stable read alias. The projection table is maintained incrementally; readers
-- do not need to know whether the projection came from a full or incremental run.
CREATE OR REPLACE VIEW `__PROJECT__.__DATASET__.inventory_on_hand_daily` AS
SELECT
  business_date,
  store_id,
  product_id,
  on_hand_units
FROM `__PROJECT__.__DATASET__.inventory_on_hand_projection`;
