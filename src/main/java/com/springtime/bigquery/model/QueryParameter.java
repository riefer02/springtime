package com.springtime.bigquery.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A named query parameter.
 *
 * <p>{@code value} is a JSON scalar. NUMERIC/BIGNUMERIC should be strings to preserve precision;
 * BYTES is base64; JSON is a JSON-encoded string.</p>
 */
public record QueryParameter(
        @NotBlank @Size(max = 64) String name,
        @NotNull ParameterType type,
        @Schema(nullable = true,
                description = "Scalar value; NUMERIC/BIGNUMERIC as string, BYTES as base64, JSON as string")
        Object value
) {
}
