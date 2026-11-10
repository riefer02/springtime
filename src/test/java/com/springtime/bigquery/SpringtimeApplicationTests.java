package com.springtime.bigquery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "bigquery.project-id=test-project",
        "app.security.api-key=test-key-0123456789abcdef0123456789"
})
@ActiveProfiles("test")
class SpringtimeApplicationTests {

    @Test
    void contextLoads() {
        // Verifies the whole application graph wires up without requiring GCP credentials.
    }
}
