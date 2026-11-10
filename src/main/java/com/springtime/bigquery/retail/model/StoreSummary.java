package com.springtime.bigquery.retail.model;

public record StoreSummary(
        String storeId,
        String storeName,
        String region,
        String city,
        String state,
        String timeZone,
        String dcId,
        String storeFormat
) {
}
