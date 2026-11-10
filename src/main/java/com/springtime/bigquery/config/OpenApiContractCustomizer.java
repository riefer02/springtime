package com.springtime.bigquery.config;

import com.springtime.bigquery.contract.ApiVersion;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Makes the generated contract stable and client-consumable: canonical relative server, explicit
 * version, RFC 9457 error schema, problem responses, and correlation/version headers.
 */
@Component
public class OpenApiContractCustomizer implements GlobalOpenApiCustomizer {

    private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";
    private static final List<String> PROBLEM_STATUSES =
            List.of("400", "401", "403", "404", "405", "406", "409", "413", "415", "429",
                    "500", "502", "503", "504");

    @Override
    public void customise(OpenAPI openApi) {
        openApi.setServers(List.of(new Server()
                .url("/")
                .description("Relative to the deployment host")));
        openApi.addExtension("x-api-version", ApiVersion.CURRENT);
        if (openApi.getInfo() != null) {
            openApi.getInfo().setVersion(ApiVersion.CONTRACT_VERSION);
            openApi.getInfo().setDescription(
                    "BigQuery query and catalog API. Generated from the running application; do not hand-edit. "
                            + "Regenerate with `make openapi`.");
        }
        openApi.getComponents().addSchemas("ProblemDetail", problemSchema());
        overrideQueryParameterValue(openApi);

        openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperationsMap().forEach((method, operation) -> enrich(operation)));
    }

    private static void overrideQueryParameterValue(OpenAPI openApi) {
        Schema<?> queryParameter = openApi.getComponents().getSchemas().get("QueryParameter");
        if (queryParameter == null || queryParameter.getProperties() == null) {
            return;
        }
        Schema<?> scalar = new Schema<>();
        scalar.setDescription("JSON scalar; NUMERIC/BIGNUMERIC as string, BYTES as base64, JSON as string");
        scalar.setOneOf(List.of(
                new StringSchema(),
                new NumberSchema(),
                new BooleanSchema(),
                new Schema<>().type("null")));
        queryParameter.getProperties().put("value", scalar);
    }

    private static void enrich(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        for (String status : PROBLEM_STATUSES) {
            operation.getResponses().addApiResponse(status, problemResponse(status));
        }
        operation.getResponses().forEach((status, response) -> {
            if (status.startsWith("2")) {
                normalizeSuccessContent(response);
            }
            addStandardHeaders(response);
        });
        if ("executeQuery".equals(operation.getOperationId())) {
            ApiResponse success = operation.getResponses().get("200");
            if (success != null) {
                success.addHeaderObject("X-BigQuery-Job-Id", new Header()
                        .description("BigQuery job id")
                        .schema(new StringSchema()));
            }
        }
    }

    private static void normalizeSuccessContent(ApiResponse response) {
        if (response.getContent() == null) {
            return;
        }
        MediaType wildcard = response.getContent().remove("*/*");
        if (wildcard != null && !response.getContent().containsKey("application/json")) {
            response.getContent().addMediaType("application/json", wildcard);
        }
    }

    private static ApiResponse problemResponse(String status) {
        ApiResponse response = new ApiResponse()
                .description(switch (status) {
                    case "400" -> "Invalid request or query";
                    case "401" -> "Authentication required";
                    case "403" -> "Insufficient scope or authority";
                    case "404" -> "Resource not found";
                    case "413" -> "Request body too large";
                    case "429" -> "Rate limited";
                    case "500" -> "Unexpected server error";
                    case "502" -> "Upstream failure";
                    case "503" -> "Service unavailable";
                    case "504" -> "Upstream timeout";
                    default -> "Error";
                })
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref(PROBLEM_REF))));
        response.addHeaderObject("Retry-After", new Header()
                .description("Retry delay in seconds when retryable=true")
                .schema(new IntegerSchema()));
        return response;
    }

    private static void addStandardHeaders(ApiResponse response) {
        response.addHeaderObject("X-Request-Id", new Header()
                .description("Request correlation id")
                .schema(new StringSchema()));
        response.addHeaderObject("X-API-Version", new Header()
                .description("API version; the path is authoritative")
                .schema(new StringSchema()));
    }

    private static Schema<?> problemSchema() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("type", new StringSchema().format("uri"));
        schema.addProperty("title", new StringSchema());
        schema.addProperty("status", new IntegerSchema());
        schema.addProperty("detail", new StringSchema());
        schema.addProperty("instance", new StringSchema().format("uri"));
        schema.addProperty("code", new StringSchema()
                .description("Stable machine-readable error code"));
        schema.addProperty("requestId", new StringSchema());
        schema.addProperty("retryable", new BooleanSchema());
        schema.addProperty("retryAfterSeconds", new IntegerSchema());
        schema.addProperty("reason", new StringSchema());
        schema.addProperty("jobId", new StringSchema());
        ObjectSchema validationError = new ObjectSchema();
        validationError.addProperty("field", new StringSchema());
        validationError.addProperty("message", new StringSchema());
        validationError.setRequired(List.of("field", "message"));
        schema.addProperty("errors", new ArraySchema().items(validationError));
        schema.setRequired(List.of(
                "type", "title", "status", "detail", "instance", "code", "requestId", "retryable"));
        return schema;
    }
}
