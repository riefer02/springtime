package com.springtime.bigquery.retail.model;

public record RetailPage<T>(
        java.util.List<T> items,
        java.time.LocalDate asOfDate,
        int pageSize,
        boolean truncated
) {
    public RetailPage {
        items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
    }
}
