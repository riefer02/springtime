# ADR 0003 — Code-first API contract, versioning, and error protocol

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The service has five operations and a small record-based DTO surface. External clients need a
pinned contract, stable machine-readable errors, and a predictable versioning story. A spec-first
generator would add duplicate DTOs, Jackson 2 assumptions, and build complexity for little benefit
at this size.

## Decisions

- **Code is the source of truth.** Springdoc generates OpenAPI 3.1 from the running application.
- **The contract is a committed artifact:** `src/main/resources/static/openapi/springtime-v1.json`,
  served at `/openapi/springtime-v1.json`.
- **Drift is a build failure.** `OpenApiContractTest` compares `/v3/api-docs` with the committed file.
  `make openapi` regenerates it; `make openapi-check` verifies it.
- **Versioning is path-based:** `/api/v1`. `X-API-Version: 1` is informational, not a negotiation
  mechanism.
- **Contract semver:** `1.0.0`. Additive change → `1.1.0`; fix → `1.0.1`; breaking change → `2.0.0`
  with `/api/v2` and `springtime-v2.json`.
- **Allowed in v1:** new optional request fields, new response fields, new endpoints, new optional
  headers, new error codes. Clients ignore unknown JSON response fields.
- **Requires v2:** removing/renaming fields, changing types/formats, making optional fields required,
  changing status codes, changing auth/scope requirements, pagination semantics, or error meaning.
- **Deprecation:** mark operations deprecated in OpenAPI, add RFC 9745 `Deprecation` and RFC 8594
  `Sunset` headers, announce in CHANGELOG, keep v1 for at least 90 days and one minor release.
- **Errors are part of v1:** `ProblemDetail` + `application/problem+json` with `code`, `requestId`,
  `retryable`, and versioned `urn:springtime:problem:v1:` types. See `docs/api/errors.md`.
- **Startup fails fast** on cross-field configuration errors with a `FailureAnalyzer` that prints
  actionable environment-variable names.

## Consequences

- Any API change is visible as a generated JSON diff and a CHANGELOG entry.
- The drift test does not detect semantic breaking changes; review and the versioning policy are the
  gate. A future `oasdiff` job against the previous release tag can add that.
- Committed contract files must not be hand-edited.
- v2 requires a new springdoc group and security matchers; do not mix versions in one aggregate
  document.
