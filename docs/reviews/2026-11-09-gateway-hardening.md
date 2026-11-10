# Gateway hardening — follow-up implementation

- **Date:** 2026-11-09
- **Follows:** [initial review](2026-11-09-initial-review.md)
- **Scope:** authentication, traffic identity, telemetry, pagination, defensive limits, and logging.
- **Result:** the service now has a gateway-grade edge contract. It is still a single stateless API
  and still needs edge TLS/rate limiting plus cloud IAM governance for a shared deployment.

## Implemented

| Area | Change |
| --- | --- |
| **Authentication** | Spring Security 7 filter chain; stateless; no Basic/form/session/CSRF. API key mode (constant-time `X-API-Key`, `ROLE_API`) and OIDC/JWT resource-server mode (`SCOPE_bigquery.query`, `SCOPE_bigquery.read`). Startup fails closed when neither is configured. |
| **Authorization** | Query and job endpoints require `ROLE_API` or `SCOPE_bigquery.query`; catalog requires `ROLE_API`, `SCOPE_bigquery.read`, or `SCOPE_bigquery.query`. Liveness/readiness are public; all other actuator endpoints require auth. |
| **Request identity** | `X-Request-Id` is validated and preserved (or a UUID generated), echoed in the response header, added to ProblemDetail as `requestId`, placed in MDC, and added to BigQuery job labels as `request_id`. |
| **Telemetry** | Structured access log (method, path, status, latency, client, remote, request id); Logback pattern with `rid`/`cid`/`job`; BigQuery job id in MDC; Prometheus metrics and build info retained. |
| **Pagination** | Catalog responses are `{items, nextPageToken, pageSize}` with configurable default/max; query responses expose `nextPageToken`; `GET /api/v1/jobs/{jobId}/results` pages an existing job. |
| **Defensive limits** | `Content-Length` body cap (413), SQL length cap, 100-parameter ceiling, per-parameter value cap, concurrency semaphore (429 + `Retry-After`), job timeout, byte/row ceilings, cache policy callers cannot weaken. |
| **Docs** | OpenAPI security schemes for API key and bearer; docs public while enabled, disabled via `SPRINGDOC_ENABLED=false`. |
| **Operations** | `make env` generates a 256-bit API key; `make key` rotates it; Compose requires a key before starting; CI smoke-tests 401, docs, and authenticated Prometheus. |

## Verification

- `./mvnw test` → **51 tests, 0 failures**, 2 opt-in real-BigQuery tests skipped.
- Security tests: missing/wrong/valid key, public liveness, protected Prometheus.
- Filter tests: request-id generation/preservation/rejection, 413 body cap.
- Pagination tests: catalog envelope, page-token forwarding, job-results page.
- Container smoke: read-only rootfs, liveness 200, docs 200, unauthenticated 401 with `X-Request-Id`,
  authenticated Prometheus 200, oversized body 413, BigQuery failure 502 with sanitized detail.
- Access-log sample verified the `rid`/`cid`/`job` fields.

## Still open

| Priority | Item |
| --- | --- |
| High | Per-key/IP rate limiting and daily/monthly byte budgets (the semaphore is global, not per client). |
| High | Idempotency key for query submission (retry after timeout still creates a second job). |
| High | Response-byte cap (row count is capped; payload size is not). |
| Medium | Async job submission, `GET/DELETE /api/v1/jobs/{jobId}`, cancellation on disconnect. |
| Medium | Custom BigQuery metrics (latency, bytes, rows, cache hit) and OpenTelemetry spans. |
| Medium | Terraform for IAM, Workload Identity, budgets, quotas, audit logs; WIF replaces mounted ADC. |
| Medium | Image digest pinning, SBOM, vulnerability scan, and signing in CI. |
| Low | CODEOWNERS, LICENSE, branch protection, signed commits (owner decisions). |

## Notes

- The API-key path is a shared secret, not user identity. Use OIDC for any multi-user deployment.
- Docs are intentionally public while `SPRINGDOC_ENABLED=true`; disable them in shared environments.
- Request size is enforced from `Content-Length`; a chunked body without a declared length must be
  bounded by the edge proxy.
