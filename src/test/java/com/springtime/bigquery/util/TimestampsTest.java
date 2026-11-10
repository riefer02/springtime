package com.springtime.bigquery.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TimestampsTest {

    @Test
    void truncatesNanosecondsThatBigQueryRejects() {
        Instant instant = Instant.parse("2026-11-10T17:01:49.395799103Z");

        assertThat(Timestamps.bigQuery(instant)).isEqualTo("2026-11-10T17:01:49.395799Z");
    }
}
