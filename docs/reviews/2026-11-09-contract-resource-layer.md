# Contract, error protocol, and resource layer — implementation note

- **Date:** 2026-11-09
- **Follows:** [initial review](2026-11-09-initial-review.md), [gateway hardening](2026-11-09-gateway-hardening.md)
- **Scope:** API contract/versioning, stable error protocol, fail-fast startup, BigQuery resource
  gateway, schema source of truth, and strict parameter typing.

## Verdict

The service now has a client-consumable contract, a stable machine-readable error protocol, and a
real resource boundary over BigQuery. We deliberately did **not** add JPA/Spring Data: BigQuery has
no entity identity, transactions, relationships, or startup schema, and the service remains a
stateless adapter. A Postgres control plane is the right later step only when durable state appears.

## Implemented

| Area | Change |
| --- | --- |
| **API contract** | OpenAPI 3.1 is generated from code, committed at `src/main/resources/static/openapi/springtime-v1.json`, served at `/openapi/springtime-v1.json`, and enforced by `OpenApiContractTest`. `make openapi` regenerates; drift fails the build. |
| **Versioning** | Path-based `/api/v1`; `X-API-Version: 1` on API responses; `info.version: 1.0.0`; policy and v2/deprecation rules in ADR 0003. |
| **Error protocol** | RFC 9457 `application/problem+json` with stable `code`, `requestId`, `retryable`, `retryAfterSeconds`, structured `errors`, `jobId`, `reason`; catalog at `docs/api/errors.md`. Security and size filters use the same factory as MVC. |
| **Fail-fast startup** | Pure `ConfigurationRules` + `SmartInitializingSingleton` validation before the web server starts; `ConfigurationValidationFailureAnalyzer` prints a `STARTUP_CONFIG_INVALID` block naming environment variables. |
| **Resource gateway** | `BigQueryGateway` port + `GoogleBigQueryGateway` adapter; services no longer call the BigQuery SDK directly for jobs, pages, or table metadata. |
| **Schema source** | Live BigQuery table schemas remain canonical; nested `subFields` and deterministic `schemaHash` are exposed. Query/catalog columns share the same mapping path. |
| **Strict types** | `ParameterType` enum with wire aliases; unknown types fail at the API boundary and appear as an enum in OpenAPI. |
| **Security shape** | OIDC bearer entry point/denied handler now explicitly use the same problem protocol; API key challenges include `WWW-Authenticate`. |
| **Tests** | 66 tests, including contract drift, startup rules, security shape, version header, schema hash, filters, and controllers. |

## Verified

- `./mvnw test` → **66 tests, 0 failures**, 3 opt-in/integration tests skipped.
- No-auth startup prints:
  `STARTUP_CONFIG_INVALID: springtime has 1 configuration problem(s).` with the `API_KEY`/OIDC
  remedies and exits before listening.
- Runtime shape: `401` includes `code=AUTHENTICATION_REQUIRED`, `requestId`, `X-API-Version`,
  `WWW-Authenticate`; `404`/`405`/`413` use the same ProblemDetail shape; `413` keeps the version
  header.
- Contract endpoint serves the committed OpenAPI 3.1 document; drift test gates changes.
- Docker image still builds; gateway and startup validation do not require ADC at boot.

## Deliberate non-goals / next

| Priority | Item |
| --- | --- |
| High | Per-key/IP rate limits and daily byte budgets (concurrency is global). |
| High | Idempotency key for query submission; POST query remains non-retryable on upstream failures. |
| High | Response-byte cap (row count is capped; payload size is not). |
| Medium | Neutral gateway records (remove SDK types from gateway signatures); split QueryService/CatalogService. |
| Medium | Async job submit/status/cancel; `DELETE /api/v1/jobs/{jobId}`. |
| Medium | Schema cache with TTL/ETag; checked-in resource allowlist with optional hash validation. |
| Medium | Postgres/Flyway control plane for durable jobs, idempotency, clients/quotas, saved queries. |
| Medium | OAS diff against the previous tag for semantic breaking-change detection; currently human review. |
| Medium | Custom BigQuery metrics/OpenTelemetry spans; Terraform/WIF; image scan/sign. |
| Low | CODEOWNERS/LICENSE/branch protection; paging error catalog coverage test. |
