package com.springtime.bigquery.web;

import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.BigQueryError;
import com.google.cloud.bigquery.JobException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Translates BigQuery SDK failures into stable application error codes and safe client detail.
 * Upstream auth failures never look like caller auth failures; 5xx details are sanitized.
 */
@Component
public class UpstreamErrorMapper {

    private static final int MAX_DETAIL_LENGTH = 500;

    public MappedError map(BigQueryException exception) {
        int code = exception.getCode();
        ErrorCode errorCode = switch (code) {
            case 400 -> ErrorCode.UPSTREAM_REJECTED;
            case 401, 403 -> ErrorCode.UPSTREAM_AUTH_FAILED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 429 -> ErrorCode.UPSTREAM_RATE_LIMITED;
            case 503 -> ErrorCode.UPSTREAM_UNAVAILABLE;
            default -> ErrorCode.UPSTREAM_ERROR;
        };
        String detail = switch (errorCode) {
            case RESOURCE_NOT_FOUND, UPSTREAM_REJECTED, CONFLICT, UPSTREAM_RATE_LIMITED ->
                    safe(exception.getMessage());
            default -> genericDetail(errorCode);
        };
        return new MappedError(errorCode, detail, exception.getReason(), null);
    }

    public MappedError map(JobException exception) {
        List<BigQueryError> errors = exception.getErrors();
        String reason = errors.isEmpty() ? null : errors.get(0).getReason();
        String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        ErrorCode errorCode;
        if (normalized.contains("invalid")) {
            errorCode = ErrorCode.UPSTREAM_REJECTED;
        } else if (normalized.contains("ratelimit") || normalized.contains("quota")) {
            errorCode = ErrorCode.UPSTREAM_RATE_LIMITED;
        } else if (normalized.contains("timeout") || normalized.contains("stopped")) {
            errorCode = ErrorCode.UPSTREAM_TIMEOUT;
        } else if (normalized.contains("backend") || normalized.contains("internal")
                || normalized.contains("unavailable")) {
            errorCode = ErrorCode.UPSTREAM_UNAVAILABLE;
        } else {
            errorCode = ErrorCode.UPSTREAM_ERROR;
        }
        String detail = switch (errorCode) {
            case UPSTREAM_REJECTED, UPSTREAM_RATE_LIMITED ->
                    safe(errors.isEmpty() ? null : errors.get(0).getMessage());
            default -> genericDetail(errorCode);
        };
        String jobId = exception.getId() == null ? null : exception.getId().getJob();
        return new MappedError(errorCode, detail, reason, jobId);
    }

    private static String genericDetail(ErrorCode code) {
        return switch (code) {
            case UPSTREAM_AUTH_FAILED ->
                    "The query service could not authenticate to BigQuery; contact the operator with the requestId.";
            case UPSTREAM_UNAVAILABLE ->
                    "The query service is temporarily unavailable; retry shortly or contact the operator.";
            case UPSTREAM_TIMEOUT ->
                    "The query did not finish within the configured timeout; the job id may be used for support.";
            default -> "The query service failed; retry later or contact the operator with the requestId.";
        };
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "The query service rejected the request.";
        }
        String cleaned = message.replaceAll("\\s+", " ").strip();
        return cleaned.length() <= MAX_DETAIL_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_DETAIL_LENGTH) + "…";
    }

    public record MappedError(ErrorCode code, String detail, String reason, String jobId) {
    }
}
