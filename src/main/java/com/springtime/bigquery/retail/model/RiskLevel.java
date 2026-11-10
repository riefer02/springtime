package com.springtime.bigquery.retail.model;

/** Risk bands used by the inventory endpoint and dashboard. */
public enum RiskLevel {
    STOCKOUT,
    CRITICAL,
    WATCH,
    NO_DEMAND,
    HEALTHY
}
