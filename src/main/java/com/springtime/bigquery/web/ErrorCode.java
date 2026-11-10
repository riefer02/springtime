package com.springtime.bigquery.web;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes. Clients switch on {@code code}; {@code title}/{@code detail}
 * are human-readable and may change. Additive codes are backward-compatible; removing or
 * repurposing a code requires a new API version.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "validation-failed", "Invalid request", RetryPolicy.NEVER),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST, "malformed-json", "Invalid request", RetryPolicy.NEVER),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", RetryPolicy.NEVER),
    INVALID_EVENT(HttpStatus.BAD_REQUEST, "invalid-event", "Invalid event", RetryPolicy.NEVER),
    INVALID_QUERY(HttpStatus.BAD_REQUEST, "invalid-query", "Invalid query", RetryPolicy.NEVER),
    UPSTREAM_REJECTED(HttpStatus.BAD_REQUEST, "upstream-rejected", "Query rejected", RetryPolicy.NEVER),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "authentication-required", "Unauthorized", RetryPolicy.NEVER),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "access-denied", "Forbidden", RetryPolicy.NEVER),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "resource-not-found", "Not found", RetryPolicy.NEVER),
    ENDPOINT_NOT_FOUND(HttpStatus.NOT_FOUND, "endpoint-not-found", "Not found", RetryPolicy.NEVER),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Method not allowed", RetryPolicy.NEVER),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "not-acceptable", "Not acceptable", RetryPolicy.NEVER),
    CONFLICT(HttpStatus.CONFLICT, "conflict", "Conflict", RetryPolicy.NEVER),
    BUSINESS_RULE_VIOLATION(HttpStatus.CONFLICT, "business-rule-violation",
            "Business rule violation", RetryPolicy.NEVER),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large", RetryPolicy.NEVER),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported-media-type",
            "Unsupported media type", RetryPolicy.NEVER),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests", RetryPolicy.ALWAYS),
    UPSTREAM_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "upstream-rate-limited",
            "Too many requests", RetryPolicy.SAFE_METHODS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error", RetryPolicy.NEVER),
    UPSTREAM_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "upstream-auth-failed",
            "Upstream authentication failed", RetryPolicy.NEVER),
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY, "upstream-error", "Upstream error", RetryPolicy.SAFE_METHODS),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
            "Service unavailable", RetryPolicy.ALWAYS),
    DATA_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "data-not-ready",
            "Data not ready", RetryPolicy.ALWAYS),
    DATASET_NOT_PROVISIONED(HttpStatus.SERVICE_UNAVAILABLE, "dataset-not-provisioned",
            "Dataset not provisioned", RetryPolicy.NEVER),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable",
            "Service unavailable", RetryPolicy.SAFE_METHODS),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout", "Upstream timeout", RetryPolicy.SAFE_METHODS);

    private final HttpStatus status;
    private final String slug;
    private final String title;
    private final RetryPolicy retryPolicy;

    ErrorCode(HttpStatus status, String slug, String title, RetryPolicy retryPolicy) {
        this.status = status;
        this.slug = slug;
        this.title = title;
        this.retryPolicy = retryPolicy;
    }

    public HttpStatus status() {
        return status;
    }

    public String slug() {
        return slug;
    }

    public String title() {
        return title;
    }

    public boolean retryable(String httpMethod) {
        return switch (retryPolicy) {
            case ALWAYS -> true;
            case SAFE_METHODS -> isSafe(httpMethod);
            case NEVER -> false;
        };
    }

    private static boolean isSafe(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)
                || "TRACE".equalsIgnoreCase(method);
    }

    private enum RetryPolicy {
        NEVER,
        ALWAYS,
        SAFE_METHODS
    }
}
