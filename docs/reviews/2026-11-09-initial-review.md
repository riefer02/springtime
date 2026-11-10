# Initial security, architecture, and operations review

- **Date:** 2026-11-09
- **Scope:** entire repository at commit-less scaffold state (`springtime`, API-only Spring Boot / BigQuery service)
- **Method:** three independent reviews (security; architecture/DRY; operations/governance) followed by
  hands-on verification and remediation. No cloud resources were created and no credentials were
  transmitted.
- **Follow-up:** see [gateway hardening](2026-11-09-gateway-hardening.md) for the auth, telemetry,
  pagination, and defensive-limit work completed after this review.
- **Verdict:** the foundation was clean and idiomatic, but it was **not safe to describe as
  production-ready**. Two issues were release-blocking: the row codec produced the wrong JSON types
  against the real client, and the dependency baseline was on an OSS end-of-life Spring Boot line.
  Both are fixed. The remaining gaps are mostly about shared-deployment security and operational
  maturity, not the core query path.

## Fixed in this pass

| Area | Finding | Resolution | Evidence |
| --- | --- | --- | --- |
| **Correctness (critical)** | `FieldValueConverter` returned BigQuery's internal string representation, so `INT64`/`FLOAT64`/`BOOL`/`NUMERIC` serialized as strings; `RANGE` threw; duplicate columns silently overwrote. | Rows are now converted by schema type (`INT64`→number, `BOOL`→boolean, `NUMERIC`→decimal, `TIMESTAMP`→RFC 3339, `BYTES`→base64), `RANGE` is a map, and duplicate names are disambiguated (`x`, `x_2`) in both rows and schema. | `FieldValueConverterTest`, `BigQueryServiceTest.mapsTypedRowsAndStatistics` |
| **Platform (high)** | Spring Boot 3.5.3 was on an OSS-EOL line (ended 2026-06-30), pulling older Tomcat/Spring/Jackson. | Upgraded to **Spring Boot 4.1.2** (Spring Framework 7.0.x, Jackson 3.1.x, Tomcat 11.0.x, Micrometer 1.17.x), **BigQuery 2.76.0**, Maven wrapper **3.9.16** with SHA-256. | `pom.xml`, `.mvn/wrapper/maven-wrapper.properties`, ADR 0001 |
| **Contract** | Parameter values used `String.valueOf`, losing NUMERIC precision and accepting objects; duplicate names were last-wins. | Dedicated `QueryParameterBinder` with typed conversion, alias support (`INTEGER`→`INT64`), duplicate rejection, object/array rejection, decimal/byte validation. | `QueryParameterBinderTest` |
| **Contract** | Schema types used legacy names while parameters used standard names. | Schema now reports standard names (`INT64`, `FLOAT64`, `BOOL`, `STRUCT`) and nested `subFields`. | `BigQueryServiceTest.describesNestedAndDuplicateColumns` |
| **Cost / reliability** | No concurrency ceiling, no job timeout, client could disable server-side caching. | Bounded `Semaphore` (default 8) with `429` + `Retry-After`; server-side `jobTimeoutMs` (default 60 s); caching policy can only be strengthened by the caller. | `BigQueryService`, `BigQueryServiceTest` |
| **Error handling** | Upstream 401/403 looked like caller auth failures; `JobException` always mapped to 400; 5xx messages leaked. | 401/403→502, rate limits→429 + `Retry-After`, job reasons map to 400/429/503/504, `jobId` included, 5xx detail sanitized, `instance` set on ProblemDetail. | `ApiExceptionHandler` |
| **Input safety** | Unknown JSON properties were silently ignored (`dryrun` typo could become a billed query). | `spring.jackson.deserialization.fail-on-unknown-properties=true`; covered by an MVC test. | `QueryControllerTest.rejectsUnknownPropertiesToPreventSilentTypos` |
| **Guard usability** | The semicolon check rejected valid queries containing `;` inside strings/comments and did not understand `#` comments. | Added a small comment/string-aware tokenizer; `SELECT ';'`, `SELECT 1 -- ;`, backticks, and `#` comments now work, while multiple statements are still rejected. | `SqlGuardTest.allowsSemicolonsInsideStringsAndComments` |
| **Health leakage** | A failed BigQuery probe echoed the raw exception text through the unauthenticated health endpoint. | Health now reports only the exception class; the full cause is logged server-side. | `BigQueryHealthIndicator` |
| **Local exposure** | Compose published `0.0.0.0:8080` with an empty API key and personal ADC. | Default bind is `127.0.0.1`; container is read-only, `/tmp` is tmpfs, capabilities dropped, `no-new-privileges`, log rotation. | `compose.yaml` |
| **Readiness** | Readiness ignored BigQuery, so a broken credential/project could still receive traffic. | `readiness` group includes `readinessState,bigquery`; liveness stays JVM-only. | `application.yml` |
| **Hygiene** | Dead ADC/project fallback; secrets paths not ignored; duplicate `org.json` on test classpath; `make run` ignored `.env`; fixed port. | Removed dead fallback, added secret ignore patterns, excluded Android JSON, `make run` loads `.env`, `PORT` supported. | `BigQueryService`, `.gitignore`, `.dockerignore`, `pom.xml`, `Makefile` |
| **Capability** | No API contract documentation; no metrics module in the Boot 4 modular layout. | OpenAPI/Swagger UI via springdoc 3.2.0; `spring-boot-starter-micrometer-metrics`; build-info enabled. | `pom.xml`, `application.yml` |
| **Governance** | No CI, dependency automation, security policy, contribution guide, ADR, or changelog. | Added GitHub Actions CI (verify + container build + smoke), Dependabot, `SECURITY.md`, `CONTRIBUTING.md`, ADR 0001, `CHANGELOG.md`. | `.github/`, `SECURITY.md`, `docs/` |

## Remaining findings

> Statuses below are from the initial review. The [gateway hardening follow-up](2026-11-09-gateway-hardening.md)
> supersedes several of them: auth, request IDs, pagination, request/parameter caps, actuator auth,
> guard tokenization, and health sanitization are now implemented.

| ID | Severity | Area | Location | Finding | Next action |
| --- | --- | --- | --- | --- | --- |
| R1 | **High (shared deployments only)** | AuthZ / tenancy | `security/SecurityConfig.java`, `service/BigQueryService.java` | API key or OIDC now establishes identity, but there is no per-tenant dataset allowlist or row-level policy; any authorized caller can read everything the runtime identity can read. | Per-tenant keys mapped to dataset allowlists (or OIDC + BigQuery RLS); one service account per trust boundary; rotate keys. |
| R2 | High | Reliability / cost | `service/BigQueryService.java` | No idempotency key: a retry after timeout/5xx creates a second billable job. | Accept `Idempotency-Key`, derive a deterministic `JobId`, return the existing job on conflict. |
| R3 | High | Availability / cost | `service/BigQueryService.java` | Concurrency is capped, but there is no per-client rate limit or aggregate daily/monthly byte budget; many cheap queries can still spend. | Gateway + token bucket per key/IP; daily budgets; BigQuery custom quotas; budget alerts. |
| R4 | High | DoS | HTTP layer, `service/BigQueryService.java` | Request body (Content-Length), SQL, parameter count, and parameter value sizes are now capped; chunked bodies without a length and response bytes remain unbounded. | Enforce a limiting input stream or edge proxy for chunked bodies; add a response-byte cap. |
| R5 | Medium | Security / ops | `application.yml` | Actuator now requires authentication except probes, but still shares the public port and shows details to authenticated callers. Loopback mitigates locally. | Separate management port/interface; `show-details: never` outside local. |
| R6 | Resolved | Capability | `api/CatalogController.java`, `api/JobController.java` | Catalog responses are paginated envelopes; query responses expose `nextPageToken`; `GET /api/v1/jobs/{jobId}/results` pages an existing job. Async submission/cancel is still open. | Add async submit + `GET/DELETE /jobs/{id}` when long-running work is required. |
| R7 | Medium | Observability | `BigQueryService` | No custom query metrics (latency, bytes, rows, cache hit, errors), structured logs, request ids, or tracing. | Micrometer timers/counters with tags; JSON logs + MDC `requestId/jobId`; OTel BigQuery spans; dashboards/alerts. |
| R8 | Medium | Cloud governance | repo | IAM, budgets, quotas, audit logs, environment separation, and WIF are documentation only. | Terraform for dev/staging/prod, dedicated SA + Workload Identity, budgets/custom quotas, audit logs, job labels with env/version. |
| R9 | Medium | Guard correctness | `service/SqlGuard.java` | The guard is still a first-token allowlist: `WITH x AS (DELETE ...) SELECT 1` and `EXPLAIN DELETE ...` pass the guard. Strings/comments are now tokenized correctly, and no write bypass was found; IAM remains the real boundary. | Keep IAM + `maximumBytesBilled` authoritative; add a real parser or BigQuery validation if the API is ever exposed to untrusted callers. |
| R10 | Medium | Health | `health/BigQueryHealthIndicator.java` | The 60 s cached probe holds a lock during network I/O and creates a dry-run job per cache miss. Error text is now sanitized. | Single-flight with timeout, inject `Clock`, optionally refresh on a schedule. |
| R11 | Medium | Supply chain | `Dockerfile` | Base images use mutable tags, not digests; no SBOM, image signing, or vulnerability scan in CI. | Pin digests (Dependabot can bump), add Trivy/Scout, Syft SBOM, cosign in the release pipeline. |
| R12 | Resolved | Routing | `security/SecurityConfig.java` | The old prefix-based filter bypass is gone; Spring Security matchers now authorize probes and API paths explicitly. | Keep matcher tests green when adding endpoints. |
| R13 | Low | Errors | `api/ApiExceptionHandler.java` | No catch-all handler; unexpected non-framework exceptions fall back to Boot's error map. | Add a catch-all that delegates `ErrorResponse` and returns a generic ProblemDetail 500 otherwise. |
| R14 | Low | API contract | `api/dto/QueryRequest.java` | `NUMERIC`/`BIGNUMERIC` should be sent as strings to preserve precision; `BYTES` must be base64; `JSON` must be a string. This is documented but easy to misuse. | Publish the wire formats in OpenAPI examples and add contract tests. |
| R15 | Low | Governance | repo root | No LICENSE, CODEOWNERS, branch protection, or signed commits yet. These need the repository owner's decisions. | Add the license, CODEOWNERS, and branch protection before opening the repo to contributors. |

## Prioritized roadmap

**Now (before any shared deployment)**

1. Require a non-empty API key outside a `local` profile; put authentication and rate limiting at
   the edge. R1, R3, R5.
2. Add request/response size caps and an `Idempotency-Key`. R2, R4.
3. Decide the job/pagination contract (`pageToken`, `GET/DELETE /jobs/{id}`). R6.
4. Bind Actuator to a private management port and turn off detailed health publicly. R5.

**Next (first month)**

5. Custom Micrometer metrics + structured logs with request/job ids. R7.
6. Terraform for dev/prod, dedicated SA + Workload Identity, budgets, quotas, audit logs. R8.
7. CI supply chain: image digest pinning, Trivy, SBOM, signing. R11.
8. Tokenize `SqlGuard` input and add property-based tests. R9.

**Later (before external clients)**

9. Per-tenant identity, dataset allowlists, key rotation. R1.
10. OpenAPI-generated client/contract tests, async job API, caching strategy, load test at BigQuery
    concurrency limits.

## Accepted trade-offs

- API-only, stateless, no database: correct for this adapter.
- First page of results with `totalRows`: adequate for exploratory use until pagination is added.
- Regex read-only guard: acceptable **only** because IAM plus `maximumBytesBilled` are the real
  controls.
- ADC mounted into the local container: appropriate for a laptop; production uses WIF/SA.
- Loopback-only Compose: deliberate; widening it requires deciding the auth story first.

## Verification

- `./mvnw test` → **39 tests, 0 failures** at initial review; **51 tests** after gateway hardening.
  2 opt-in real-BigQuery tests are skipped in both runs.
- `./mvnw verify` → green.
- Docker image builds on `maven:3.9.16-eclipse-temurin-21` / `eclipse-temurin:21-jre-alpine`.
- Container smoke test: liveness `UP`, `/v3/api-docs` reachable, API-key 401 path verified,
  read-only rejection verified, unknown-field 400 verified.
- Real BigQuery execution was **not** possible in this environment because no
  `application_default_credentials.json` exists; the opt-in integration test is ready for a project
  with ADC.
