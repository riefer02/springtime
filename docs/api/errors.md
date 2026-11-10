# Error protocol (v1)

All errors use `application/problem+json` and RFC 9457 `ProblemDetail`.

## Shape

```json
{
  "type": "urn:springtime:problem:v1:invalid-query",
  "title": "Invalid query",
  "status": 400,
  "detail": "Only SELECT, WITH, EXPLAIN, DESCRIBE and SHOW statements are allowed in read-only mode.",
  "instance": "/api/v1/query",
  "code": "INVALID_QUERY",
  "requestId": "6f2c1a4e-6c7e-4a3f-9e2d-0e6f3b1d7c88",
  "retryable": false
}
```

Required extension members:

| Member | Meaning |
| --- | --- |
| `code` | Stable machine-readable code. Switch on this, not `title`/`detail`. |
| `requestId` | Same value as the `X-Request-Id` header and logs. Include it in support requests. |
| `retryable` | Whether retrying the **same request** is safe. |
| `retryAfterSeconds` | Present only when `retryable=true`; mirrors `Retry-After`. |
| `errors` | Present for validation failures: `[{"field":"sql","message":"sql is required"}]`. |
| `jobId` | Present for BigQuery job failures/timeouts where safe. |
| `reason` | Machine-readable upstream reason, e.g. `invalidQuery`. |

Clients must ignore unknown members and unknown `code` values. Additive codes are non-breaking;
removing or repurposing a code requires a new API version.

Headers:

- `X-Request-Id` on every response
- `X-API-Version: 1` on API responses (the path is authoritative)
- `Cache-Control: no-store`
- `Retry-After` only when `retryable=true`
- `WWW-Authenticate` on security-filter 401/403; an invalid `X-API-Key` rejected directly by the
  key filter currently returns only the problem body

## Code catalog

| Code | HTTP | Retryable | Meaning |
| --- | ---: | --- | --- |
| `VALIDATION_FAILED` | 400 | no | Bean validation failed; see `errors` |
| `MALFORMED_JSON` | 400 | no | Missing/invalid JSON, unknown field, invalid enum |
| `INVALID_REQUEST` | 400 | no | Missing, type-mismatched, or malformed request input (including a local offset `pageToken`) |
| `INVALID_EVENT` | 400 | no | Retail event failed semantic validation (window, quantity, identifiers, payload) |
| `INVALID_QUERY` | 400 | no | Local SQL policy or limit violation |
| `UPSTREAM_REJECTED` | 400 | no | BigQuery rejected the query |
| `AUTHENTICATION_REQUIRED` | 401 | no | Missing/invalid API key or bearer token |
| `ACCESS_DENIED` | 403 | no | Valid identity, insufficient scope/authority |
| `RESOURCE_NOT_FOUND` | 404 | no | Dataset, table, job, store, order, run, transmission, or dead letter not found |
| `ENDPOINT_NOT_FOUND` | 404 | no | Unknown route |
| `METHOD_NOT_ALLOWED` | 405 | no | Wrong HTTP method |
| `NOT_ACCEPTABLE` | 406 | no | Accept negotiation failed |
| `CONFLICT` | 409 | no | Local or upstream state conflict (idempotency, version, transition, or a unique-constraint violation) |
| `BUSINESS_RULE_VIOLATION` | 409 | no | A business rule forbids the request regardless of retries (for example "only a FAILED transmission can be retried") |
| `PAYLOAD_TOO_LARGE` | 413 | no | Body exceeds `MAX_REQUEST_BYTES` |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | no | Non-JSON request body |
| `RATE_LIMITED` | 429 | yes | Local concurrency budget exhausted |
| `UPSTREAM_RATE_LIMITED` | 429 | safe methods | BigQuery quota/rate limit |
| `INTERNAL_ERROR` | 500 | no | Unexpected server failure |
| `UPSTREAM_AUTH_FAILED` | 502 | no | BigQuery credentials/IAM rejected |
| `UPSTREAM_ERROR` | 502 | safe methods | BigQuery 5xx/unknown failure |
| `SERVICE_UNAVAILABLE` | 503 | yes | Local overload/shutdown, message broker rejection, or control-plane database unavailable |
| `UPSTREAM_UNAVAILABLE` | 503 | safe methods | BigQuery unavailable |
| `UPSTREAM_TIMEOUT` | 504 | safe methods | Query/job timed out |
| `DATA_NOT_READY` | 503 | yes | Retail data has not been loaded for the requested scope |
| `DATASET_NOT_PROVISIONED` | 503 | no | A retail read found the owned dataset or table missing; run the schema/seed job, then retry manually |

Retail triage: an unknown store is `404 RESOURCE_NOT_FOUND` and must not be treated as healthy;
`503 DATASET_NOT_PROVISIONED` means a retail read found the owned dataset/table missing (run the
schema/seed job, then retry manually); `503 DATA_NOT_READY` means no snapshot or ledger data exists
yet for the scope (retry after the next load; an existing store with no rows is just an empty page).
`409 CONFLICT` and `409 BUSINESS_RULE_VIOLATION` are never retryable without changing the request or
the stored state.

“Safe methods” means `GET`, `HEAD`, `OPTIONS`, `TRACE`. **`POST /api/v1/query` is non-idempotent and
billable**, so upstream 5xx/429/504 responses are not retryable until an idempotency key exists.
On a 504, use `jobId` to inspect the job before retrying. Retries of
`GET /api/v1/jobs/{jobId}/results` are safe. Upstream failures log the error code, reason, `jobId`,
and mapped code; the upstream stack trace is not logged.

## Status mapping

Upstream 401/403 are **not** exposed as caller 401/403; they map to `502 UPSTREAM_AUTH_FAILED`
because only the operator can fix credentials/IAM. Upstream 5xx details are generic; the full
exception and upstream reason are logged with the same `requestId`.

## Versioning

The error protocol is versioned by the `type` URN namespace (`urn:springtime:problem:v1:`). It is
stable for API v1. A breaking change to the error schema (member removal/type change) requires a new
problem version and a new API major version. Adding a code is additive and documented here.

## Example: validation

```bash
curl -s -X POST localhost:8080/api/v1/query \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{}'
```

```json
{
  "type": "urn:springtime:problem:v1:validation-failed",
  "title": "Invalid request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/query",
  "code": "VALIDATION_FAILED",
  "requestId": "...",
  "retryable": false,
  "errors": [{"field": "sql", "message": "sql is required"}]
}
```
