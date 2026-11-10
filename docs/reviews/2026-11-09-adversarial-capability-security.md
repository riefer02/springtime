# Adversarial capability and security review — springtime

> **Implementation follow-up:** the correctness spine from this review is now implemented:
> append-only `inventory_movements`, idempotent `MERGE` sink, consumer/local relay, structured
> dead-letter envelope, durable dead-letter replay, incremental on-hand projection, replenishment
> runs/proposals, proposal decisions, purchase orders, reconciliation, and ArchUnit module checks.
> See
> [ADR 0010](../adr/0010-inventory-ledger-and-idempotent-sink.md),
> [ADR 0011](../adr/0011-dlq-replay-and-incremental-projection.md), and
> [docs/architecture/modules.md](../architecture/modules.md). The remaining P0/P1 items
> (per-client identity, rate limits, query idempotency, response caps, non-durable local broker)
> are still open.

- **Date:** 2026-11-09
- **Scope:** the whole service: query/catalog API, retail v1, event intake, broker/retry/DLQ
  primitives, auth, limits, contract, container, CI, and operational story.
- **Method:** static review of the current working tree plus the existing test suite. No live GCP
  project or ADC was available, so BigQuery execution, the retail seed, Pub/Sub delivery, and IAM
  behavior are **not** verified end-to-end.
- **Bias:** assume the demo works. Then ask what breaks, what is overclaimed, and what a hostile or
  careless caller can do.

## Verdict

This is a competent **single-tenant, read-only BigQuery demo service** with a better-than-average
contract and error story. It is **not** a production retail copilot yet.

The biggest problems are not in the SQL or the Spring wiring. They are:

1. **The async story is a façade.** The API returns `202` and publishes, but nothing consumes the
   topic, and the default sink only logs. There is no end-to-end event processing.
2. **The default broker silently loses events.** The in-memory publisher is selected when Pub/Sub is
   not explicitly enabled, drops the oldest message at capacity, and still returns success.
3. **One shared API key is a full operator credential.** It grants arbitrary SQL, catalog browsing,
   retail reads, and event writes, with no per-client identity, quota, revocation, or audit.
4. **The authorization model overgrants.** A Workspace `hd` or an allow-listed service-account `sub`
   becomes `ROLE_API`, which is the operator surface, not a retail-reader role.
5. **The retail data path has never run against BigQuery.** The SQL, seed, KPI definitions, date
   windows, and cost profile are untested. `mvn verify` proves the wiring, not the data.

If this is shown as a demo, say exactly that. Do not describe it as production-ready, durable,
exactly-once, multi-tenant, or rate-limited.

---

## Capability: demo vs production

| Area | Demo capability | Production capability | Gap |
| --- | --- | --- | --- |
| Query / dry-run | Works with ADC | Needs per-client auth, quotas, idempotency, response caps | Auth is a shared key; no idempotency; no byte cap |
| Catalog browsing | Lists datasets/tables/schemas | Needs dataset allowlist and tenant separation | Any `ROLE_API` caller sees everything the SA can see |
| Retail reads | Stores/inventory/replenishment/dashboard over seed | Needs tenant scoping, keyset pagination, freshness, live SQL validation | No tenant model; `LIMIT` is not pagination; seed unrun |
| Event intake | Returns `202` and a message id | Needs durable ingress, consumer, sink, dedupe, DLQ replay | No consumer; no BigQuery sink; default sink logs |
| Retry | Bounded attempts with backoff + jitter | Needs transient/permanent classification and idempotency | Retries every `RuntimeException` |
| DLQ | Publishes exhausted events to a topic | Needs native policy, replay tooling, alerting | Publish failure is swallowed; no replay/alert |
| Auth | API key locally; OIDC/JWT production | Needs per-client identity, scopes, revocation, audit | `hd`/`sub` → `ROLE_API`; shared key is god mode |
| Observability | Access logs, health, generic actuator metrics | Needs queue/DLQ/retry/cost metrics, alerts, SLOs | No custom metrics; no queue depth or DLQ signal |
| Supply chain | Dependabot, container build | Needs image scan, SBOM, coverage gate, pinned digests | None present |
| Operations | Compose, loopback, non-root, read-only rootfs | Needs IaC, WIF, secret manager, backups, runbooks | `gcloud` commands and docs only |

---

## P0 — critical: data loss, false claims, or full operator access

### P0-1. The async pipeline is not end-to-end; `202` promises processing that never happens

**Evidence**
- `src/main/java/com/springtime/bigquery/retail/service/RetailEventProcessor.java:36` defines
  `process(EventEnvelope)`, but no code path invokes it. A repository-wide grep finds no subscriber,
  listener, scheduler, or controller calling `process`.
- `src/main/java/com/springtime/bigquery/messaging/PubSubMessagePublisher.java:21-47` only creates a
  `Publisher`; there is no `Subscriber`, subscription, or pull loop.
- `src/main/java/com/springtime/bigquery/retail/service/LoggingRetailEventSink.java:9-18` is the only
  `RetailEventSink` bean. It logs and returns, so even a wired consumer would acknowledge without
  persisting.
- `src/main/java/com/springtime/bigquery/messaging/InMemoryMessagePublisher.java:16-35` stores
  messages in a local queue and never delivers them.

**Failure / attack scenario**
A client posts a sale event and receives `202` with `status: ACCEPTED`. Nothing processes it. With
the in-memory broker, the event sits in a queue until eviction or restart. With Pub/Sub, it sits in
the topic until subscription retention expires. The client has no status endpoint, no callback, and
no way to discover that the event was never applied. If a dashboard later reads inventory, the sale
is missing and the KPI is wrong. This is worse than a `500`: it is a successful-looking no-op.

**Fix**
- Implement a real subscriber: Pub/Sub push/pull or a Cloud Run worker that calls
  `RetailEventProcessor.process`.
- Implement a `BigQueryRetailEventSink` that writes with `MERGE` on `event_id`; acknowledge only after
  the commit.
- Until that exists, either remove `POST /api/v1/retail/events` from the contract or return a
  documented `503 SERVICE_UNAVAILABLE` / `501 NOT_IMPLEMENTED` instead of `202`.
- Add a processing-status endpoint (`GET /api/v1/retail/events/{eventId}`) backed by the dedupe table
  so `202` is verifiable.

### P0-2. The default broker silently drops events and is selected by omission

**Evidence**
- `src/main/java/com/springtime/bigquery/messaging/InMemoryMessagePublisher.java:17`:
  `@ConditionalOnProperty(..., havingValue = "false", matchIfMissing = true)`.
- `InMemoryMessagePublisher.java:28-35`: when `messages.size() >= maxMessages`, it calls
  `messages.poll()` (drops the oldest) and then returns a new `messageId` as if the publish succeeded.
- `src/main/resources/application.yml:93-97`: `messaging.pubsub-enabled` defaults to `false`.

**Failure / attack scenario**
A deployment forgets `MESSAGING_PUBSUB_ENABLED=true`, or misspells it. The app starts cleanly on the
in-memory broker. A burst of 1,001 events evicts the oldest event with no error and no metric; the
client already received `202`. A restart loses everything. An attacker can intentionally flood the
endpoint to evict legitimate events from the queue.

**Fix**
- Make the in-memory broker local/test-only (`@Profile("local")` or a dedicated property) and **fail
  startup** in any non-local profile when Pub/Sub is disabled.
- Replace the queue with a bounded `ArrayBlockingQueue`/`LinkedBlockingQueue` and `offer`; when full,
  throw `MessagingException` so the API returns `503`, or apply backpressure. Never drop silently.
- Add a broker health indicator and a `messaging.in-memory.dropped` counter.
- Test the full/eviction path explicitly; there is currently no test for `InMemoryMessagePublisher`.

### P0-3. DLQ routing can lose the event, and permanent failures are not distinguished

**Evidence**
- `src/main/java/com/springtime/bigquery/retail/service/RetailEventProcessor.java:42-50`: on
  `RetryExhaustedException`, it tries `publisher.publish(deadLetterTopic, event)` and catches any
  `RuntimeException`, only logging it.
- `RetailEventProcessor.java:36-41`: it catches only `RetryExhaustedException`. A
  `RetryInterruptedException` from `ThreadSleeper` (interruption during backoff) is not caught and
  bypasses DLQ routing.
- `src/main/java/com/springtime/bigquery/messaging/RetryExecutor.java:29-34`: catches every
  `RuntimeException`, including permanent validation and programming errors.
- The DLQ payload is the original envelope only; it carries no attempt count, failure class, or
  original topic.

**Failure / attack scenario**
The sink is down and the DLQ topic is misconfigured or the worker lacks `pubsub.publisher` on it.
The event is retried, exhausted, then the DLQ publish fails. The processor logs and returns, so the
broker acknowledges the message and the event is gone. Separately, a poison event (unknown store,
bad payload) is retried five times with backoff before DLQ, which is wasted latency and noise.
During shutdown, an interrupt bypasses the DLQ entirely.

**Fix**
- Never swallow a DLQ publish failure: rethrow so the broker does not acknowledge and redelivers, or
  persist to a durable fallback (local spool/outbox) before returning.
- Add a `DeadLetterEnvelope` with `originalTopic`, `attempts`, `lastErrorClass`, `failedAt`, and
  `requestId` so replay is possible.
- Classify exceptions: retry only known transient failures (BigQuery 5xx/429/timeout, network); send
  validation/permanent failures to the DLQ immediately.
- Catch interruption explicitly and route/requeue rather than dropping.
- Add metrics and alerts for DLQ publishes, failures, and depth.

### P0-4. One shared API key is a full operator credential

**Evidence**
- `src/main/java/com/springtime/bigquery/security/ApiKeyAuthenticationFilter.java:58-63`: any valid
  key becomes principal `"api-key"` with `ROLE_API`.
- `src/main/java/com/springtime/bigquery/security/SecurityConfig.java:87-96`: `ROLE_API` can call
  `/api/v1/query`, `/api/v1/jobs/**`, `/api/v1/datasets/**`, all retail reads, and event writes.
- `src/main/java/com/springtime/bigquery/config/AppSecurityProperties.java:31-38`: only two keys
  (`API_KEY`, `API_KEY_PREVIOUS`); no key IDs, scopes, quotas, or per-key revocation.

**Failure / attack scenario**
The key is a single secret in `.env`, Compose, or the process environment. If it leaks (developer
laptop, CI variable, `docker inspect`, a copied `.env`), the attacker gets arbitrary `SELECT`, catalog
enumeration, retail data, and event injection. Every request looks like `api-key` in the access log,
so there is no attribution. Revoking one client means rotating the key for everyone. There is no
rate limit, so the attacker can run expensive queries and publish events until the bill or quota
stops them.

**Fix**
- Move to per-client credentials: OIDC client credentials or hashed API keys with a key ID, scopes,
  expiry, and per-key quotas.
- Log the key ID (never the secret) as `clientId`; support revocation without redeploying.
- Reserve `ROLE_API` for a local operator; do not grant it to every shared key.
- Put a hard edge rate limit and daily budget in front of `/api/v1/query` and the event endpoint.
- Keep `API_KEY` for local only; production should use OIDC + Workload Identity.

---

## P1 — high: overgrant, cost abuse, data exposure, correctness

### P1-1. Workspace domain / service-account subject maps to `ROLE_API`, not retail read

**Evidence**
- `SecurityConfig.java:126-143`: a JWT with `hd` in `oidcAllowedDomains`, or `sub` in
  `oidcAllowedSubjects`, gets `ROLE_API`.
- `docs/auth/README.md:36-37` documents this as intended.
- `SecurityConfig.java:87-96` then lets that principal use the generic query and catalog surfaces.

**Failure / attack scenario**
`OIDC_ALLOWED_DOMAINS=example.com` is configured so employees can see the retail dashboard. Every
employee in that domain can also call `POST /api/v1/query` with arbitrary SQL and
`GET /api/v1/datasets` to enumerate the catalog. The blast radius is the runtime service account's
IAM. If that SA has project-level `dataViewer`, the employee can read every dataset in the project,
not just retail. A compromised employee account becomes a data-exfiltration path.

**Fix**
- Map `hd` and non-operator `sub` to `SCOPE_retail.read` only, or to a dedicated `retail-user` role.
- Require an explicit `roles` claim (e.g. `bigquery.query`) for the operator surface; do not infer
  operator access from domain membership.
- Use separate service accounts/audiences for the retail API and the operator query API.
- Add negative tests: allowed domain + no role ⇒ retail reads only; no query/catalog access.

### P1-2. Arbitrary SQL is allowed; the read-only guard is a prefix regex, and the byte cap does not bound all cost

**Evidence**
- `src/main/java/com/springtime/bigquery/service/SqlGuard.java:19-36`: a regex accepts a statement
  that starts with `select`, `with`, `explain`, `describe`, or `show`.
- `src/main/java/com/springtime/bigquery/service/BigQueryService.java:89-91`: the guard runs only when
  `readOnly` is true; `:104-111` sets `maximumBytesBilled`, `maxResults`, timeout, and cache.
- `README.md` already says IAM is the real boundary and the guard is not a parser.

**Failure / attack scenario**
A `ROLE_API` caller can submit `SELECT` statements that invoke:
- `EXTERNAL_QUERY(...)` (egress to an external database),
- remote functions / Cloud Run-backed UDFs,
- `ML.GENERATE_TEXT` / Vertex AI functions,
- BigLake/external tables.

`maximumBytesBilled` caps bytes scanned by the query; it does not cap remote function invocations,
Vertex AI token spend, external-query egress, or slot time. A cheap-looking `SELECT` can therefore
create a large bill or move data outside BigQuery. The guard also cannot distinguish a safe
`SELECT` from a data-exfiltration `SELECT` over `INFORMATION_SCHEMA` or any readable table.

**Fix**
- Treat `/api/v1/query` as an operator-only surface with a separate identity, audience, and scope.
- Add a real SQL parser or a stricter allowlist of tables/functions; deny `EXTERNAL_QUERY`, remote
  functions, `ML.*`, and `INFORMATION_SCHEMA` unless explicitly needed.
- Keep the runtime SA dataset-scoped and read-only; do not rely on `BIGQUERY_READ_ONLY`.
- Add budget alerts, BigQuery custom quotas, and per-key/day byte and job budgets.
- Document that `maximumBytesBilled` is not a total cost cap.

### P1-3. Job results have no caller binding

**Evidence**
- `src/main/java/com/springtime/bigquery/api/JobController.java:29-36` exposes
  `GET /api/v1/jobs/{jobId}/results`.
- `src/main/java/com/springtime/bigquery/service/BigQueryService.java:215-232` builds
  `JobId.of(properties.projectId(), jobId)`, calls `gateway.getJob`, and returns the rows. It never
  checks who created the job.
- `SecurityConfig.java:93-94` allows any `ROLE_API` or `SCOPE_bigquery.query` caller.

**Failure / attack scenario**
Job IDs are returned in `X-BigQuery-Job-Id`, the response body, error `jobId` fields, support
tickets, and logs. If two clients/tenants share the deployment, one can fetch another's job results
if it learns the ID. The ID is a UUID, so it is not trivially guessable, but it is not a secret and
there is no ownership check. The same pattern applies to catalog paths: any caller can enumerate
datasets/tables the SA can read.

**Fix**
- Bind jobs to the caller: store `jobId -> principal/tenant` in a small durable store (or return a
  signed job token) and check it before returning results.
- Alternatively, make the results endpoint stateless by returning signed page tokens and not
  accepting arbitrary job IDs.
- Validate `jobId` as a UUID and reject unexpected characters before calling the SDK.
- Add a negative test: caller B cannot read caller A's job.

### P1-4. No rate limits or quotas; the "concurrency cap" is per instance and query-only

**Evidence**
- `src/main/java/com/springtime/bigquery/service/BigQueryService.java:63,75` creates a `Semaphore`
  with `maxConcurrentQueries`.
- `BigQueryService.java:234-243` acquires a slot; exhaustion throws `TooManyRequestsException`,
  mapped to `429 RATE_LIMITED`.
- `src/main/java/com/springtime/bigquery/config/BigQueryProperties.java:41-42` describes it as
  "across all callers"; it is per JVM.
- There is no rate limiter, token bucket, daily budget, per-key quota, or IP limit anywhere in
  `src/main`. Retail and event endpoints have no limiter at all.

**Failure / attack scenario**
One key sends eight concurrent expensive queries and keeps them running; each instance allows eight,
and autoscaling multiplies that across instances. BigQuery costs and quotas are consumed; other
callers get `429`. The event endpoint has no limit, so a flood can evict the in-memory queue or run
up Pub/Sub costs. `RATE_LIMITED` in the error catalog implies request-rate limiting that does not
exist.

**Fix**
- Add per-key and per-IP token buckets at the edge (API gateway/Cloud Armor) and in the app for
  defense in depth.
- Add daily/monthly byte and job budgets per key; fail closed when exhausted.
- Add bounded queue depth/backpressure for event intake.
- Rename or document the semaphore as a per-instance concurrency gate, not a rate limit.
- Emit `429` with `Retry-After` consistently.

### P1-5. Query submission is non-idempotent; retries double-bill and can double-apply

**Evidence**
- `BigQueryService.java:245-249`: every request creates a random job ID:
  `JobId.of(UUID.randomUUID().toString())`.
- `docs/api/errors.md:71-72` explicitly says `POST /api/v1/query` is non-idempotent and billable, and
  upstream 5xx/429/504 are not retryable until an idempotency key exists.
- There is no `Idempotency-Key` handling in `QueryController` or `BigQueryService`.

**Failure / attack scenario**
A client times out after BigQuery accepted the job, then retries. Two jobs run; the bytes are billed
twice. With `BIGQUERY_READ_ONLY=false`, a duplicate `INSERT`/`MERGE`-less DML can double-apply. This
is exactly the case where a retry is most tempting (timeout) and most dangerous.

**Fix**
- Accept an `Idempotency-Key` header, map it to a deterministic job ID or a stored response, and
  return the prior result for a replay.
- Keep the query API read-only; do not enable write mode there.
- Document retry safety per endpoint; consider `GET /jobs/{id}` for status after a timeout.

### P1-6. `BIGQUERY_READ_ONLY=false` is a single environment switch to arbitrary DDL/DML

**Evidence**
- `src/main/java/com/springtime/bigquery/config/ConfigurationRules.java:138-141`: turning read-only
  off produces only a warning.
- `BigQueryService.java:89-91`: the SQL guard is skipped when `readOnly` is false.

**Failure / attack scenario**
An operator flips the flag to run a one-off migration, or a misconfigured deployment sets
`BIGQUERY_READ_ONLY=false`. Now any `ROLE_API` caller can run `DROP TABLE`, `DELETE`, `EXPORT DATA`,
or DDL. There is no separate admin scope, confirmation, or audit. The warning is easy to miss in
logs.

**Fix**
- Remove write mode from the public query API; put DDL/DML in a separate admin surface with a
  separate identity and explicit `SCOPE_bigquery.admin`.
- Fail startup if `BIGQUERY_READ_ONLY=false` outside a local profile.
- Log and alert on every write statement; consider a two-person approval for destructive DDL.
- Keep IAM read-only regardless.

### P1-7. Event intake is syntactically validated only; no semantic validation, versioning, dedupe, or clock bounds

**Evidence**
- `src/main/java/com/springtime/bigquery/retail/api/dto/RetailEventRequest.java:15-28` validates
  presence, max length, enum, and non-negative quantity. It does not validate that the store or
  product exists, that the event type has the required fields, or that `occurredAt` is sane.
- `src/main/java/com/springtime/bigquery/retail/service/RetailEventPublisher.java:21-33` hardcodes
  `schemaVersion` to `"1"`; the request has no version field and no version negotiation.
- There is no intake-side dedupe: the same `eventId` can be published repeatedly, including with a
  different payload. The docs say the sink must dedupe, but no sink exists.

**Failure / attack scenario**
A future-dated `SALE` skews the 28-day sales KPI. A `SALE` with `quantity=null` or no `productId` is
accepted. An event for `ST-999` writes an orphan row or fails in the sink. The same `eventId` with a
different body is accepted, so the consumer cannot tell replay from conflict. A v2 producer is
indistinguishable from v1 because the envelope always says `"1"`.

**Fix**
- Validate semantics at intake: allowed store/product, required fields per event type, `quantity`
  bounds, `occurredAt` within an allowed clock skew and retention window.
- Add `schemaVersion` to the request and reject unsupported versions; define a compatibility policy.
- Add an intake dedupe/conflict check by `eventId` (or explicitly document that duplicates are
  accepted and only the sink dedupes).
- Add a `payload` depth/size limit, not just 50 keys.
- Add tests for future dates, unknown store/SKU, missing type-specific fields, duplicate IDs, and
  unsupported versions.

### P1-8. `maxResults` is not a response-byte or memory bound

**Evidence**
- `BigQueryService.java:93-98` clamps `maxResults`, and `:325-333` materializes every returned row
  into `List<Map<String, Object>>`.
- `README.md` admits "response byte size is not capped yet."
- `RequestSizeLimitFilter` bounds inbound bytes only.

**Failure / attack scenario**
A caller runs `SELECT` over a table with large `STRING`/`BYTES`/`JSON` columns. Even 1,000 rows can
produce tens or hundreds of megabytes after conversion and serialization, causing heap pressure,
long GC pauses, or OOM. `maxResults` does not bound the serialized size. A single key can repeat
this.

**Fix**
- Cap serialized response bytes per request and per row; truncate or reject with a documented error.
- Stream large results instead of materializing them, or require pagination for large fields.
- Add a `maxFieldValueLength`/`maxResponseBytes` setting and a test with a large row.
- Consider BigQuery's Storage Read API for large exports, outside the request path.

---

## P2 — medium: correctness, hardening, operability

| ID | Finding | Evidence | Scenario | Fix |
| --- | --- | --- | --- | --- |
| P2-1 | `RetryExecutor` retries permanent failures and programming errors | `RetryExecutor.java:29-34` catches every `RuntimeException` | Invalid event/NPE retried 5× with backoff before DLQ; duplicate side effects if the sink partially wrote | Retry only a transient predicate; fail fast on validation; require idempotency; test interruption and permanent errors |
| P2-2 | In-memory queue is O(n) per publish and racy at the bound | `InMemoryMessagePublisher.java:29-33` calls `size()` in a loop and `poll()`/`add()` separately | At 100k capacity, each publish traverses the queue; concurrent publishers can exceed the bound | Use a bounded blocking queue with `offer`/`poll`; atomic counter; fail closed when full |
| P2-3 | Request body limit can be bypassed with chunked transfer | `RequestSizeLimitFilter.java:36-41` checks `Content-Length` only; comment admits it | A chunked JSON body is read without a hard byte limit, causing memory/CPU DoS | Wrap the input stream with a counting limit; configure Tomcat `max-swallow-size`/`max-http-form-post-size`; reject missing length for JSON |
| P2-4 | Successful API responses lack `Cache-Control: no-store` | `ApiProblemFactory.java:48-54` sets it for problems only; no global cache filter | A shared proxy/browser caches retail/KPI/query results and serves them to another user | Set `Cache-Control: no-store` on all `/api/**` responses; test |
| P2-5 | Job/catalog path parameters are not format-validated | `JobController.java:31`; `BigQueryService.java:179,198,217` pass raw strings to `JobId.of`/`DatasetId.of`/`TableId.of` | Malformed IDs cause upstream errors; odd characters may be interpreted by the SDK/URL path; enumeration is easier | Regex-validate BigQuery dataset/table IDs and UUID job IDs; reject `..`, `/`, backticks; test |
| P2-6 | Retail pagination is a hard `LIMIT`, not pagination | `RetailController.java:36-77`; `RetailPage.java:3-10` has no next token; SQL ends with `LIMIT @row_limit` | A store with >200 SKUs is silently truncated; replenishment undercounts; no `hasMore` | Keyset pagination on `(risk_rank, days_of_supply, sku)`; return `nextPageToken`/`hasMore`; or explicitly document top-N |
| P2-7 | Date/freshness handling is permissive and expensive | `RetailService.java:108-110` only defaults; `latest_snapshot.sql:1-4` uses `MAX(snapshot_date)` with `>= 2000-01-01`; inventory CTE uses a 7-day window | `asOfDate=2099-01-01` returns an empty page with no error; stale data returns empty; the latest query scans all partitions; every request without a date runs an extra query | Validate a date range; return `DATA_NOT_READY`/freshness metadata; cache the latest date; use `INFORMATION_SCHEMA.PARTITIONS` or a metadata table |
| P2-8 | Pub/Sub shutdown can drop in-flight messages | `PubSubMessagePublisher.java:59-64` calls `shutdown()` but not `awaitTermination` | Scale-in/shutdown after `202` loses messages still in the publisher's batch | Await termination with a bounded timeout; retry transient publishes; test graceful shutdown |
| P2-9 | No queue/retry/DLQ/cost metrics or alerts | No `MeterRegistry`/`Counter`/`Timer` usage in `src/main`; only actuator defaults; `README.md` promises Prometheus metrics | DLQ depth, retry exhaustion, evictions, publish failures, bytes billed, and job latency are invisible; no alerting | Add Micrometer counters/gauges/timers: published, processed, retried, DLQ, evictions, query bytes/duration, job failures; alert on DLQ/evictions |
| P2-10 | Log injection via event identifiers | `LoggingRetailEventSink.java:16-17` and `RetailEventProcessor.java:43-44,48-49` log `eventId`/`storeId`; `RetailEventRequest.java:16,19` only checks length | An `eventId`/`storeId` containing newlines forges log lines and confuses incident response | Restrict identifiers to a safe charset (e.g. `[A-Za-z0-9._:-]`); sanitize before logging |
| P2-11 | Contract drift test was weakened to accept any success status | `OpenApiContractTest.java:83` uses `has("200") || has("202")`; `:89-93` accepts either | A regression that changes a GET to `202` passes the drift test | Assert `200` for all non-event operations and `202` only for `publishRetailEvent` |
| P2-12 | Retail seed is destructive and has never run | `Makefile:85-96` uses `CREATE OR REPLACE TABLE` and `bq mk ... || true`; `db/retail/seed/*.sql`; `BigQueryIntegrationTest.java:30-51` tests only `SELECT 1` | A `.env` pointed at a shared/prod dataset replaces tables; a SQL error is discovered only during the demo; KPI numbers may not reconcile | Require `ALLOW_RETAIL_SEED=true` and a `_demo` dataset suffix; use a separate project; run `bq query --dry_run` in CI; add reconciliation tests; document synthetic data |
| P2-13 | No image scanning, SBOM, dependency audit, or coverage gate | `pom.xml:93-116` has only Boot/surefire; `.github/workflows/ci.yml` builds/runs but does not scan; no JaCoCo | A vulnerable transitive dependency or base image ships unnoticed; coverage can regress silently | Add OWASP dependency-check or Trivy/Grype, Syft SBOM, JaCoCo threshold, and a pinned image digest |
| P2-14 | No CORS configuration | No `CorsConfigurationSource`/`cors()` in `SecurityConfig` | A separate browser dashboard origin cannot call the API; someone may disable security to "fix" it | Add an explicit allowlist for known origins; keep credentials off; test preflight; or document same-origin only |
| P2-15 | Forwarded headers are trusted from any peer | `application.yml:4` sets `forward-headers-strategy: framework`; `AccessLogFilter.java:54` logs `request.getRemoteAddr()` | If directly exposed, a client can spoof `X-Forwarded-For`/`Proto`/`Host`, poisoning logs and any future IP rate limit | Trust forwarded headers only behind a known proxy; configure Cloud Run/native strategy; log both peer and forwarded values |
| P2-16 | Readiness fails on a BigQuery outage, removing all instances | `application.yml:38-39` includes `bigquery` in readiness; `BigQueryHealthIndicator` runs a dry-run job | A BigQuery outage marks every instance unready and causes a total outage, even for endpoints that could return `503` | Decide explicitly: keep BigQuery as a separate indicator or cached detail; do not fail readiness for a dependency outage unless fail-fast is desired |
| P2-17 | OpenAPI/Swagger are public and enabled in the local stack | `SecurityConfig.java:84-86` permits `/v3/api-docs/**`, `/swagger-ui/**`, `/openapi/**`; `application.yml:105-113` defaults springdoc off; `compose.yaml:50` sets it on | A production deployment with `SPRINGDOC_ENABLED=true` exposes the full schema and interactive docs unauthenticated | Keep docs disabled in prod; require auth or remove the routes; test that prod config returns 404/401 |

---

## P3 — low: polish, documentation accuracy, future hazards

| ID | Finding | Evidence | Fix |
| --- | --- | --- | --- |
| P3-1 | Client can weaken a server "no cache" policy | `BigQueryService.java:99-102`: `properties.useQueryCache() || request.useQueryCache()`; if the server sets `false`, a client `true` still enables cache | Use `properties.useQueryCache() && request.useQueryCache()` (or a tri-state policy) if the server value is a hard policy; update the README claim |
| P3-2 | `X-Request-Id` is caller-controlled and accepted, not rejected | `RequestIdFilter.java:29-36` accepts 8–64 chars or generates; no signature | Only trust it from authenticated/known clients; otherwise generate; document that it is correlation, not identity |
| P3-3 | Config docs overstate the concurrency limit | `BigQueryProperties.java:41-42` says "across all callers"; the semaphore is per instance | Reword to "per instance"; document aggregate concurrency under autoscaling |
| P3-4 | Error contract over-documents irrelevant statuses | `OpenApiContractTest.java:84-88` requires `404`, `409`, etc. on operations where they cannot occur | Make per-operation response sets realistic; keep the global catalog separate |
| P3-5 | Retry properties lack positive-duration validation | `RetryProperties.java:17-21`: `initialDelay` and `maxDelay` have no `@Positive`/cross-field rule; `maxDelay < initialDelay` is allowed | Add startup validation: positive delays, `maxDelay >= initialDelay`, sane multiplier; test |
| P3-6 | DLQ topic can equal the events topic | `MessagingProperties.java:13-18` has no cross-field check | Fail startup if `deadLetterTopic.equals(eventsTopic)`; add a bounded DLQ-to-DLQ escape hatch |
| P3-7 | Dataset name is not charset-validated | `RetailProperties.java:14-20` only `@NotBlank`; `BigQueryRetailAdapter.java:48-50` substitutes it into SQL | Validate BigQuery dataset ID charset/length; keep `sqlName` non-user-controlled |
| P3-8 | README says "validated X-Request-Id" but the filter accepts or generates | `README.md` security section; `RequestIdFilter.java:34-36` | Reword to "accepted when well formed, otherwise generated" |
| P3-9 | Seed dates are fixed to 2026-11-09 | `db/retail/seed/10_sales.sql:4`, `20_inventory.sql:11`, etc. | Make the anchor date configurable or refresh to `CURRENT_DATE()` for demos; document that data is synthetic and static |
| P3-10 | No API deprecation/sunset policy | Contract docs; no `Deprecation`/`Sunset` headers | Add a versioning/deprecation policy before v2 |

---

## What actually holds up

This is not a hit piece; several things are genuinely good and worth keeping:

- **Fixed, parameterized retail SQL.** `RetailService` passes `storeId`, `asOfDate`, `category`,
  `riskLevel`, and limits as typed parameters; dataset/project are config-owned, not
  request-supplied. This is the right shape and avoids the usual "LLM writes SQL" injection mess.
- **The read-only guard is a useful first line.** It rejects multi-statement input and non-`SELECT`
  prefixes, and the docs correctly say IAM is the real boundary. It is not sufficient alone, but it
  is honest.
- **Error protocol.** RFC 9457, stable `code`, `requestId`, `retryable`, `Retry-After`, and a
  documented catalog. Upstream auth failures are not exposed as caller 401/403. This is better than
  most demos.
- **Auth mechanics.** Stateless, no sessions/Basic/CSRF, constant-time key comparison, previous-key
  rotation, issuer HTTPS and audience validation at startup.
- **Limits are clamped down.** Callers cannot raise row, byte, concurrency, or parameter ceilings.
- **Contract discipline.** Committed OpenAPI 3.1 with a drift test; the event endpoint is in the
  contract. The test needs tightening (P2-11), but the discipline is right.
- **Container hygiene.** Non-root, read-only rootfs, `cap_drop: ALL`, `no-new-privileges`, loopback
  Compose binding, health probes.
- **Honest docs.** `README.md` and the ADRs already list many deferred items; the problem is that the
  code's happy path can still imply more than the docs say.

---

## What not to claim

Use this list as a guardrail for the demo, README, and interviews. Items marked **resolved** were
addressed by the follow-up implementation; the rest are still open.

- **Resolved:** "events are processed asynchronously." The consumer/ledger sink now exists; see ADR
  0010. Do not claim exactly-once, though.
- **Not** "durable" or "guaranteed delivery" while the default broker is in-memory.
- **Not** "exactly-once." At-least-once is the design; ledger dedupe handles duplicates.
- **Not** "rate-limited." There is a per-instance concurrency gate on queries, not a request rate
  limit.
- **Not** "multi-tenant." Retail has no tenant/store-level authorization.
- **Not** "retail data is validated." The seed has not run against BigQuery in this environment.
- **Not** "production-ready for untrusted clients." The README already says this; keep saying it.
- **Partially resolved:** the DLQ has a structured envelope, routing, and metrics; native policy
  tuning, replay tooling, and alerting still need work.

---

## Priority fix sequence

### Stop the bleeding (before the next demo)

1. **Profile-gate the in-memory broker.** Fail startup in non-local profiles when Pub/Sub is
   disabled. Make a full queue return `503`, never drop.
2. **Disable or downgrade the event endpoint** until a consumer and sink exist. `202` must mean
   something.
3. **Remove the logging sink from production wiring.** A sink that acknowledges without persistence
   is a data-loss trap.
4. **Revert the contract-test relaxation** (P2-11) and add per-operation success assertions.
5. **Map `hd`/`sub` to retail read only**; require an explicit role for the query/catalog surface.

### Make the async pipeline real

6. Pub/Sub subscription + Cloud Run worker (or push endpoint) → `RetailEventProcessor`.
7. `BigQueryRetailEventSink` with `MERGE` on `event_id`; ack only after commit.
8. Intake dedupe/conflict detection; `schemaVersion` validation; semantic validation.
9. DLQ envelope with failure metadata; replay tool; DLQ depth/alerting.
10. End-to-end test with the Pub/Sub emulator and a BigQuery test dataset (or a fake gateway).

### Make the query surface safe to expose

11. Per-client identity/scopes/quotas; separate operator and retail audiences.
12. Job-result caller binding; format validation for job/dataset/table IDs.
13. Idempotency key for query submission; keep write mode off the public surface.
14. SQL parser/allowlist; deny external/remote/AI functions; budget alerts and custom quotas.
15. Response-byte cap; `Cache-Control: no-store` on all API responses.

### Make retail correct and usable

16. Tenant/store authorization (or explicitly document single-tenant).
17. Keyset pagination with `nextPageToken`/`hasMore`.
18. Date-range validation, freshness metadata, cached latest snapshot, partition-pruned latest
    query.
19. Run the seed in a disposable project; add `bq query --dry_run` and reconciliation tests.

### Make it operable

20. Queue/retry/DLQ/cost metrics and alerts; `clientId` and `surface` job labels.
21. Image scanning, SBOM, dependency audit, coverage gate, pinned base image digest.
22. Load/chaos tests: 1k events, broker full, DLQ failure, BigQuery timeout, 429 storm.
23. IaC (Terraform), WIF instead of mounted ADC, secret manager, runbooks/SLOs.

---

## Verification gaps

The current suite is good on the happy path and weak on the failure modes that matter here. Add
tests for:

- `InMemoryMessagePublisher`: full queue, eviction, concurrent publish, restart semantics.
- `RetailEventProcessor`: DLQ publish failure, interruption, permanent vs transient errors, duplicate
  delivery, sink partial write.
- `RetryExecutor`: permanent failure predicate, interruption, jitter band (the current test only
  checks `0..maxDelay`).
- `RetailEventPublisher`: envelope mapping, version, future `occurredAt`, unknown store/SKU,
  duplicate `eventId` with a different payload.
- `RequestSizeLimitFilter`: chunked body without `Content-Length`.
- Global cache headers, CORS preflight, log-injection identifiers.
- `JobController`: caller A cannot read caller B's job.
- Auth matrix: allowed `hd` + no role ⇒ retail read only; `SCOPE_retail.read` cannot query/catalog;
  `ROLE_API` can; expired/rotated keys.
- Contract: exact success status per operation; event `202` only.
- Live BigQuery: seed load, all four retail SQL files, `asOfDate` edge cases, cost/bytes, `404` →
  `DATA_NOT_READY`.
- Pub/Sub: publish, redelivery, DLQ after max attempts, shutdown flush.

---

## Bottom line

The architecture is directionally right, and the core query path is better than typical demo code.
But the current system has a **false async story**, a **silent-loss default broker**, a **god-key auth
model**, and an **unverified retail data path**. Those are the four things to fix before calling this
a retail copilot rather than a BigQuery CRUD demo.

The shortest credible path is: make the broker fail closed, implement the consumer/sink, narrow
`hd`/`sub` to retail read, and run the seed in a disposable project. Everything else is hardening on
top of a system that then does what it says.
