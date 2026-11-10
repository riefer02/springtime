package com.springtime.bigquery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hits the real BigQuery API. Opt in with {@code RUN_BIGQUERY_IT=true} and valid ADC plus
 * {@code GOOGLE_CLOUD_PROJECT}. Dry runs are free; the default probe is a dry run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.api-key=it-key-0123456789abcdef0123456789")
@EnabledIfEnvironmentVariable(named = "RUN_BIGQUERY_IT", matches = "true")
@ActiveProfiles("test")
class BigQueryIntegrationTest {

    private final RestClient client = RestClient.create();

    @LocalServerPort
    private int port;

    @Test
    void dryRunReportsBytesWithoutExecuting() {
        ResponseEntity<Map> response = post(Map.of("sql", "SELECT 1 AS one", "dryRun", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("dryRun")).isEqualTo(true);
        assertThat(response.getBody().get("jobId")).isNotNull();
    }

    @Test
    void executesSelectOneWithTypedValues() {
        ResponseEntity<Map> response = post(Map.of("sql", "SELECT 1 AS one, 1.5 AS half, TRUE AS flag"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) ((List<?>) response.getBody().get("rows")).get(0);
        assertThat(row.get("one")).isEqualTo(1);
        assertThat(row.get("half")).isEqualTo(1.5);
        assertThat(row.get("flag")).isEqualTo(true);
    }

    private ResponseEntity<Map> post(Map<String, ?> body) {
        return client.post()
                .uri("http://localhost:%d/api/v1/query".formatted(port))
                .header("X-API-Key", "it-key-0123456789abcdef0123456789")
                .body(body)
                .retrieve()
                .toEntity(Map.class);
    }
}
