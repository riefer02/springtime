# ADR 0002 — Authentication, traffic identity, pagination, and defensive limits

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The service is moving from a laptop demo to something that can sit behind a gateway. That requires
three things the initial scaffold did not have:

1. a real authentication/authorization story (not a single optional header with no identity),
2. a way to identify and follow a request across logs, BigQuery jobs, and error responses, and
3. bounded inputs/pages so a caller cannot exhaust memory, BigQuery quota, or the JVM.

## Decisions

### Authentication and authorization

- **Spring Security 7**, stateless: no session, no form login, no HTTP Basic, CSRF disabled (machine
  API with no cookies).
- **Local mode:** `X-API-Key`, constant-time comparison, grants `ROLE_API`. `make env` generates one;
  the application refuses to start if no authentication mechanism is configured.
- **Production mode:** OIDC/JWT resource server using Spring Boot's standard
  `spring.security.oauth2.resourceserver.jwt.*` properties (issuer + audience). Scopes map to
  `SCOPE_*` authorities.
- **Authorization:**
  - `/api/v1/query` and `/api/v1/jobs/**` require `ROLE_API` or `SCOPE_bigquery.query`.
  - `/api/v1/datasets/**` require `ROLE_API`, `SCOPE_bigquery.read`, or `SCOPE_bigquery.query`.
  - Actuator probes (`liveness`, `readiness`) are public; all other actuator endpoints require auth.
  - OpenAPI/Swagger UI is public when enabled; disable springdoc in shared environments.

### Traffic identity and telemetry

- Every request gets an `X-Request-Id`: a valid caller value is preserved, otherwise a UUID is
  generated. It is returned in the response header, placed in MDC, included in problem responses,
  and added to BigQuery job labels as `request_id` (`r-` + sanitized id).
- Logs are JSON on stdout (Logstash encoder behind a bounded async appender). Access lines record
  method, concrete path, matched route, status, latency, client, remote address, and request id;
  application lines carry the allow-listed MDC fields `requestId`, `clientId`, `jobId`, `traceId`,
  and `spanId`.
- BigQuery job ids are placed in MDC while a job is active.
- Prometheus metrics and build info remain available. Response-byte truncation is implemented
  (`MAX_RESPONSE_BYTES` → `truncated=true`, no `nextPageToken`).

### Pagination

- Catalog endpoints return `{items, nextPageToken, pageSize}`.
- Query responses include `nextPageToken`; additional pages are fetched from
  `GET /api/v1/jobs/{jobId}/results?pageToken=...&pageSize=...`.
- `app.pagination.default-page-size` and `app.pagination.max-page-size` set the default and hard
  ceiling; callers cannot exceed the ceiling.

### Defensive limits

- `app.http.max-request-bytes` (Content-Length guard, 413).
- `app.http.max-sql-length`.
- `app.http.max-parameter-value-length` plus a 100-parameter list ceiling and bounded name/type
  lengths.
- BigQuery concurrency semaphore (429), job timeout, byte ceiling, row ceiling, and a cache policy
  callers cannot weaken.

## Consequences

- Local development now requires `API_KEY`; `make env` creates one, and `make key` rotates it.
- API consumers must send `X-API-Key` (or a bearer token) for `/api/v1/**` and non-probe actuator
  endpoints.
- Docs are public by design but are disabled entirely in shared environments via
  `SPRINGDOC_ENABLED=false`.
- Per-key rate limits, daily budgets, idempotency keys, response-byte caps, and async job
  cancellation remain follow-ups; the gateway should add TLS, WAF, and global rate limiting.
