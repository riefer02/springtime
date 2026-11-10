package com.springtime.bigquery.retail.model;

import java.time.LocalDate;

public record InventoryPosition(
        String storeId,
        String storeName,
        String region,
        String productId,
        String sku,
        String productName,
        String category,
        LocalDate snapshotDate,
        long onHandUnits,
        long inTransitUnits,
        long reservedUnits,
        long availableUnits,
        long safetyStockUnits,
        long reorderPointUnits,
        double avgDailyUnits,
        Double daysOfSupply,
        long leadTimeDays,
        String riskLevel,
        LocalDate projectedStockoutDate,
        String unitCost,
        String inventoryValue
) {
}
