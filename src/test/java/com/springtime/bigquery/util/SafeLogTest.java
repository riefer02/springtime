package com.springtime.bigquery.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeLogTest {

    @Test
    void stripsLineBreaksAndTabs() {
        assertThat(SafeLog.id("evt\nforged\tline")).isEqualTo("evt_forged_line");
    }

    @Test
    void truncatesLongIdentifiers() {
        assertThat(SafeLog.id("x".repeat(100))).hasSize(64);
    }

    @Test
    void handlesNull() {
        assertThat(SafeLog.id(null)).isEqualTo("-");
    }
}
