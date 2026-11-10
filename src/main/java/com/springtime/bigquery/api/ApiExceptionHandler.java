package com.springtime.bigquery.api;

import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.JobException;
import com.springtime.bigquery.model.query.ValidationError;
import com.springtime.bigquery.error.InvalidEventException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.error.BusinessRuleException;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.DataNotReadyException;
import com.springtime.bigquery.error.DatasetNotProvisionedException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.error.TooManyRequestsException;
import com.springtime.bigquery.error.UpstreamUnavailableException;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.web.ApiProblemFactory;
import com.springtime.bigquery.web.ErrorCode;
import com.springtime.bigquery.web.UpstreamErrorMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps application, upstream, and framework failures onto the versioned RFC 9457 protocol. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ApiProblemFactory problemFactory;
    private final UpstreamErrorMapper upstreamErrorMapper;

    public ApiExceptionHandler(ApiProblemFactory problemFactory, UpstreamErrorMapper upstreamErrorMapper) {
        this.problemFactory = problemFactory;
        this.upstreamErrorMapper = upstreamErrorMapper;
    }

    @ExceptionHandler(InvalidQueryException.class)
    ResponseEntity<ProblemDetail> handleInvalidQuery(InvalidQueryException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.INVALID_QUERY, exception.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ProblemDetail> handleInvalidRequest(
            InvalidRequestException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(InvalidEventException.class)
    ResponseEntity<ProblemDetail> handleInvalidEvent(InvalidEventException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.INVALID_EVENT, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ProblemDetail> handleConflict(ConflictException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<ProblemDetail> handleBusinessRule(
            BusinessRuleException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.BUSINESS_RULE_VIOLATION,
                exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problemFactory.problem(
                request, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), error.getDefaultMessage()))
                .toList());
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .headers(problemFactory.headers(request, ErrorCode.VALIDATION_FAILED))
                .body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.MALFORMED_JSON,
                "Request body is missing, not valid JSON, or contains an unknown field");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.INVALID_REQUEST,
                "Invalid value for parameter '%s'".formatted(exception.getName()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.RESOURCE_NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(DatasetNotProvisionedException.class)
    ResponseEntity<ProblemDetail> handleDatasetNotProvisioned(
            DatasetNotProvisionedException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.DATASET_NOT_PROVISIONED,
                exception.getMessage());
    }

    @ExceptionHandler(DataNotReadyException.class)
    ResponseEntity<ProblemDetail> handleDataNotReady(DataNotReadyException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.DATA_NOT_READY,
                "Retail data is not loaded for the requested scope; run the seed or wait for the next load.");
    }

    @ExceptionHandler(MessagingException.class)
    ResponseEntity<ProblemDetail> handleMessaging(MessagingException exception, HttpServletRequest request) {
        log.warn("Messaging publish failed: {}", exception.getMessage(), exception);
        return problemFactory.response(request, ErrorCode.SERVICE_UNAVAILABLE,
                "The event could not be accepted for processing; retry shortly");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrity(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("control-plane constraint violation: {}", exception.getClass().getSimpleName());
        return problemFactory.response(request, ErrorCode.CONFLICT,
                "The request conflicts with existing control-plane data");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDataAccess(
            DataAccessException exception, HttpServletRequest request) {
        log.warn("control-plane database unavailable: {}", exception.getClass().getSimpleName());
        return problemFactory.response(request, ErrorCode.SERVICE_UNAVAILABLE,
                "The control plane is temporarily unavailable; retry shortly");
    }

    @ExceptionHandler(BigQueryException.class)
    ResponseEntity<ProblemDetail> handleBigQuery(BigQueryException exception, HttpServletRequest request) {
        UpstreamErrorMapper.MappedError mapped = upstreamErrorMapper.map(exception);
        log.warn("BigQuery call failed code={} reason={} mapped={}",
                exception.getCode(), exception.getReason(), mapped.code());
        return mappedResponse(request, mapped);
    }

    @ExceptionHandler(JobException.class)
    ResponseEntity<ProblemDetail> handleJobFailure(JobException exception, HttpServletRequest request) {
        UpstreamErrorMapper.MappedError mapped = upstreamErrorMapper.map(exception);
        log.warn("BigQuery job failed jobId={} mapped={}", mapped.jobId(), mapped.code());
        return mappedResponse(request, mapped);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyRequests(
            TooManyRequestsException exception, HttpServletRequest request) {
        return problemFactory.response(request, ErrorCode.RATE_LIMITED, exception.getMessage());
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(
            UpstreamUnavailableException exception, HttpServletRequest request) {
        log.warn("Upstream unavailable: {}", exception.getMessage(), exception);
        return problemFactory.response(request, ErrorCode.UPSTREAM_UNAVAILABLE,
                "The query service is temporarily unavailable; retry shortly or contact the operator.");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException exception, HttpServletRequest request) {
        log.error("Service is misconfigured or in an invalid state", exception);
        return problemFactory.response(request, ErrorCode.INTERNAL_ERROR,
                "The service could not complete the request");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse errorResponse) {
            ProblemDetail body = errorResponse.getBody();
            HttpStatus status = HttpStatus.resolve(body.getStatus());
            ErrorCode code = ApiProblemFactory.forFrameworkStatus(
                    status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status);
            String detail = code == ErrorCode.ENDPOINT_NOT_FOUND
                    ? "No endpoint at " + request.getRequestURI()
                    : body.getDetail() == null ? code.title() : body.getDetail();
            ResponseEntity<ProblemDetail> response = problemFactory.response(request, code, detail);

            HttpHeaders headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            headers.putAll(errorResponse.getHeaders());
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        }

        log.error("Unhandled exception", exception);
        return problemFactory.response(request, ErrorCode.INTERNAL_ERROR,
                "The service could not complete the request");
    }

    private ResponseEntity<ProblemDetail> mappedResponse(
            HttpServletRequest request, UpstreamErrorMapper.MappedError mapped) {
        ProblemDetail problem = problemFactory.problem(request, mapped.code(), mapped.detail());
        if (mapped.reason() != null) {
            problem.setProperty("reason", mapped.reason());
        }
        if (mapped.jobId() != null) {
            problem.setProperty("jobId", mapped.jobId());
        }
        return ResponseEntity.status(mapped.code().status())
                .headers(problemFactory.headers(request, mapped.code()))
                .body(problem);
    }
}
