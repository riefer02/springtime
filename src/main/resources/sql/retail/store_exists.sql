SELECT COUNT(*) AS store_count
FROM `__PROJECT__.__DATASET__.stores`
WHERE store_id = @store_id;
