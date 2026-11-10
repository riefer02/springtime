package com.springtime.bigquery.model.query;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.springtime.bigquery.model.QueryParameter;

import java.util.List;

/**
 * Request body for {@code POST /api/v1/query}.
 *
 * <p>All optional fields fall back to the server defaults; client-supplied limits are clamped to
 * the server ceiling so a caller can never raise the cost or row budget.</p>
 */
public record QueryRequest(
        @NotBlank(message = "sql is required")
        @Size(max = 1_024_000, message = "sql must be at most 1024000 characters")
        String sql,

        @Size(max = 10_000, message = "parameters must contain at most 10000 entries")
        List<@Valid @NotNull QueryParameter> parameters,

        @Min(value = 1, message = "maxResults must be at least 1")
        @Max(value = 100_000, message = "maxResults must be at most 100000")
        Long maxResults,

        @Positive(message = "maximumBytesBilled must be positive")
        Long maximumBytesBilled,

        Boolean dryRun,

        Boolean useQueryCache
) {
    public QueryRequest {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
