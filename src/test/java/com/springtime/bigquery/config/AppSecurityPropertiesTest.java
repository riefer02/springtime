package com.springtime.bigquery.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppSecurityPropertiesTest {

    @Test
    void toStringRedactsApiKeys() {
        AppSecurityProperties properties = new AppSecurityProperties(
                "secret-0123456789abcdef0123456789",
                "previous-0123456789abcdef0123456789",
                false, "roles", List.of("example.com"), List.of("subject"), false,
                "admin-0123456789abcdef0123456789");

        assertThat(properties.toString())
                .doesNotContain("secret-0123456789abcdef0123456789")
                .doesNotContain("previous-0123456789abcdef0123456789")
                .doesNotContain("admin-0123456789abcdef0123456789")
                .contains("apiKey=***", "adminApiKey=***");
    }
}
