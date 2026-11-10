package com.springtime.bigquery.retail.event.model;

/** What the consumer should do with a message after processing. */
public enum ProcessingResult {
    PROCESSED,
    DUPLICATE,
    DEAD_LETTERED
}
