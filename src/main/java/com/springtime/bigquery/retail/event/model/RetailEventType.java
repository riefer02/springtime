package com.springtime.bigquery.retail.event.model;

/** Event types accepted by the retail event intake. */
public enum RetailEventType {
    SALE,
    RETURN,
    INVENTORY_ADJUSTMENT,
    SHIPMENT,
    OPENING_BALANCE
}
