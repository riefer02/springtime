package com.springtime.bigquery.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Single source of truth for the RFC 9457 error shape and response headers. Used by MVC advice,
 * Spring Security handlers, and servlet filters so all clients see the same protocol.
 */
@Component
public class ApiProblemFactory {

    private static final String RETRY_AFTER_SECONDS = "5";

    public ProblemDetail problem(HttpServletRequest request, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create(ProblemTypes.V1 + code.slug()));
        try {
            problem.setInstance(URI.create(request.getRequestURI()));
        } catch (IllegalArgumentException exception) {
            problem.setInstance(URI.create("/"));
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        problem.setProperty("code", code.name());
        boolean retryable = code.retryable(request.getMethod());
        problem.setProperty("retryable", retryable);
        if (retryable) {
            problem.setProperty("retryAfterSeconds", Integer.parseInt(RETRY_AFTER_SECONDS));
        }
        return problem;
    }

    public ResponseEntity<ProblemDetail> response(HttpServletRequest request, ErrorCode code, String detail) {
        ProblemDetail problem = problem(request, code, detail);
        return ResponseEntity.status(code.status())
                .headers(headers(request, code))
                .body(problem);
    }

    public HttpHeaders headers(HttpServletRequest request, ErrorCode code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        if (code.retryable(request.getMethod())) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return headers;
    }

    public static ErrorCode forFrameworkStatus(HttpStatus status) {
        if (status == HttpStatus.METHOD_NOT_ALLOWED) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status == HttpStatus.NOT_ACCEPTABLE) {
            return ErrorCode.NOT_ACCEPTABLE;
        }
        if (status == HttpStatus.UNSUPPORTED_MEDIA_TYPE) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        if (status == HttpStatus.NOT_FOUND) {
            return ErrorCode.ENDPOINT_NOT_FOUND;
        }
        if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
            return ErrorCode.PAYLOAD_TOO_LARGE;
        }
        if (status.is4xxClientError()) {
            return ErrorCode.INVALID_REQUEST;
        }
        return ErrorCode.INTERNAL_ERROR;
    }
}
