package com.springtime.bigquery.retail.model;

import java.time.LocalDate;

public record RetailDashboard(
        String storeId,
        LocalDate asOfDate,
        long onHandUnits,
        String inventoryValue,
        long stockoutSkus,
        long lowStockSkus,
        long units28d,
        String netSales28d,
        long openLateShipments,
        long deliveredLateLines,
        long activePromotions
) {
}
