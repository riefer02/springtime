package com.springtime.bigquery.contract;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed OpenAPI contract is a generated artifact. Normal runs fail if the running app's
 * contract differs; {@code make openapi} (or -Dopenapi.write=true) regenerates it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.api-key=contract-key-0123456789abcdef0123456789",
        "bigquery.project-id=contract-project",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@ActiveProfiles("test")
class OpenApiContractTest {

    private static final Path CONTRACT =
            Path.of("src/main/resources/static/openapi/springtime-v1.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String API_KEY = "contract-key-0123456789abcdef0123456789";

    private final RestClient client = RestClient.create();

    @LocalServerPort
    private int port;

    @Test
    void committedContractMatchesRuntime() throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("openapi.write"),
                "contract regeneration run");

        JsonNode runtime = fetch("/v3/api-docs");
        JsonNode committed = JSON.readTree(CONTRACT.toFile());

        assertThat(runtime)
                .as("OpenAPI contract drift: run `make openapi` and commit the regenerated file")
                .isEqualTo(committed);
    }

    @Test
    @EnabledIfSystemProperty(named = "openapi.write", matches = "true")
    void writeContract() throws Exception {
        JsonNode runtime = fetch("/v3/api-docs");
        Files.createDirectories(CONTRACT.getParent());
        String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(runtime);
        Files.writeString(CONTRACT, pretty + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    @Test
    void servedContractMatchesCommitted() throws Exception {
        JsonNode served = fetch("/openapi/springtime-v1.json", API_KEY);
        JsonNode committed = JSON.readTree(CONTRACT.toFile());
        assertThat(served).isEqualTo(committed);
    }

    @Test
    void validatesAsOpenApi31AndDocumentsProblemProtocol() throws Exception {
        JsonNode spec = fetch("/v3/api-docs");

        assertThat(spec.path("openapi").asText()).isEqualTo("3.1.0");
        assertThat(spec.path("info").path("version").asText()).isEqualTo("1.0.0");
        assertThat(spec.path("x-api-version").asText()).isEqualTo("1");
        assertThat(spec.path("servers").get(0).path("url").asText()).isEqualTo("/");
        assertThat(spec.path("paths").size()).isEqualTo(33);
        assertThat(spec.path("components").path("schemas").has("ProblemDetail")).isTrue();

        spec.path("paths").properties().forEach(pathEntry -> {
            String path = pathEntry.getKey();
            pathEntry.getValue().properties().forEach(operationEntry -> {
                String method = operationEntry.getKey();
                JsonNode responses = operationEntry.getValue().path("responses");
                boolean eventEndpoint = path.equals("/api/v1/retail/events")
                        || path.equals("/api/v1/retail/purchase-orders/{poId}/transmit");
                boolean accepted = eventEndpoint && method.equals("post");
                String successStatus = accepted ? "202" : "200";
                assertThat(responses.has(successStatus))
                        .as("%s %s must document %s", method, path, successStatus)
                        .isTrue();
                for (String status : new String[]{"400", "401", "403", "404", "405", "406", "409", "413", "415", "429", "500", "502", "503", "504"}) {
                    assertThat(responses.has(status))
                            .as("problem response %s on %s %s", status, method, path)
                            .isTrue();
                }
                JsonNode success = responses.get(successStatus);
                assertThat(success).isNotNull();
                assertThat(success.path("content").has("application/json")).isTrue();
            });
        });
    }

    private JsonNode fetch(String path) throws IOException {
        return fetch(path, null);
    }

    private JsonNode fetch(String path, String apiKey) throws IOException {
        var request = client.get()
                .uri("http://localhost:%d%s".formatted(port, path));
        if (apiKey != null) {
            request = request.header("X-API-Key", apiKey);
        }
        String body = request.retrieve().body(String.class);
        return JSON.readTree(body);
    }
}
