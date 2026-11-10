package com.springtime.bigquery.retail.model;

public record ReplenishmentRecommendation(
        String storeId,
        String storeName,
        String productId,
        String sku,
        String productName,
        String category,
        String supplierId,
        String supplierName,
        long availableUnits,
        double avgDailyUnits,
        Double daysOfSupply,
        long leadTimeDays,
        long effectiveReorderPoint,
        long recommendedUnits,
        String orderValue
) {
}
