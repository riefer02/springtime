# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [Unreleased]

### Added

- **Read-only demo console:** `/demo/index.html` is a static vanilla-JS page with a store picker,
  KPIs, inventory risk, replenishment, recent runs, and purchase orders. It uses the read-only key
  from session storage, calls only GET endpoints, and requires no build step or CORS.
- **Operations contract:** `docs/operations/README.md` defines SLOs, error-budget policy, a
  dependency failure matrix, capacity/cost tuning, and runbooks; `ops/prometheus/springtime-alerts.yml`
  holds the alert rules, and a contract test fails if an alert references a renamed metric.
- **Projection freshness SLO:** `retail.inventory.projection.last.success.timestamp.seconds` is a
  gauge updated on every successful refresh.
- **Local observability stack:** `compose.observability.yaml` adds Prometheus, Grafana
  (provisioned datasource + SLO dashboard), an OTel collector, and an authenticated metrics proxy;
  `make obs-up` starts it and `make obs-config` validates the configuration.
- **Load tests:** k6 scenarios (smoke, event intake, retail reads, mixed) with SLO thresholds,
  runnable through `make loadtest`.
- **Failure injection:** local scripts for Postgres outage, broker saturation, and an unreachable
  BigQuery project, asserting status codes and recovery (`make chaos`).
- **Resilience tests:** broker-full intake, transient upstream outage, and 50-concurrent-write
  coalescing without loss or rejection.
- OpenAPI 3.1 committed contract + drift test, `X-API-Version`, and canonical contract endpoint.
- Stable RFC 9457 error protocol with `code`, `requestId`, `retryable`, catalog, and sanitized
  upstream mapping.
- Fail-fast cross-field startup validation with an actionable failure analyzer.
- `BigQueryGateway` resource port, deterministic `schemaHash`, and strict `ParameterType` enum.
- Multi-key API rotation (`API_KEY_PREVIOUS`) and Google/GCIP OIDC authority mapping
  (`roles` claim, Workspace `hd`, service-account `sub`).
- `AGENTS.md`, ADR 0005 (BigQuery migrations/rollback policy), ADR 0006 (Google auth), and ADR 0007
  (async events/long-running workers) plus the async-events architecture guide.
- Retail copilot v1: `stores`, `inventory`, `replenishment`, and `dashboard` read endpoints over a
  deterministic BigQuery retail seed (`db/retail/seed`), with `retail.read` scopes and a typed
  `RetailService`/`RetailQueryPort` path that reuses the shared query guard and limits. ADR 0008.
- Retail event intake: `POST /api/v1/retail/events`, in-memory/Pub/Sub publisher, event idempotency
  key, bounded exponential backoff + jitter, and DLQ routing. ADR 0009 / `docs/messaging`.
- **Inventory movement ledger:** append-only `inventory_movements` table with `event_id` as the
  idempotency key (MERGE key `(event_id, business_date)`); `inventory_movements_deduped` and
  `inventory_on_hand_daily` read models; seed bridge for reconciliation. ADR 0010.
- **Idempotent consumer/sink:** `RetailEventProcessor` translates events to signed movements;
  `BigQueryInventoryMovementSink` uses one `MERGE` on `(event_id, business_date)`; duplicate events
  are acknowledged as `DUPLICATE`. Pub/Sub subscriber and local relay ack only after commit.
- **Structured DLQ:** `DeadLetterEnvelope` includes original topic, attempts, reason, error type, and
  failure time; DLQ publish failure is transient so the broker redelivers.
- **Dead-letter replay:** durable `retail_event_dead_letters` table, in-memory local store,
  `GET /api/v1/retail/events/dead-letters`, and
  `POST /api/v1/retail/events/dead-letters/{eventId}/replay` (admin-only). Replay is idempotent via
  the ledger `MERGE`.
- **Incremental on-hand projection:** `inventory_on_hand_projection` table, stable
  `inventory_on_hand_daily` view, `POST /api/v1/retail/inventory/projection` with full/incremental
  modes and a late-event lookback, plus an opt-in scheduler. ADR 0011.
- **Replenishment runs/proposals:** durable `replenishment_runs` and `replenishment_proposals`
  tables; `POST /api/v1/retail/replenishment-runs` creates/replaces a bounded single-store run with
  `run_id` idempotency; read endpoints for run status and prioritized proposals.
- **Proposal decisions:** `PROPOSED`/`APPROVED`/`REJECTED`/`ORDERED` state machine with
  `expectedVersion` optimistic concurrency, admin/write-only transitions, an `orderReference`
  marker, and append-only `replenishment_proposal_decisions` history. ADR 0013.
- **Purchase orders:** `POST /api/v1/retail/purchase-orders` creates a draft from approved
  proposals for one run/store/supplier with an idempotency key, copied lines, totals, and
  `purchase_order_events` lifecycle audit; `submit`/`cancel` are draft-only and version-guarded.
  Supplier EDI/API transmission and receiving are deferred. ADR 0014.
- **Postgres control plane:** Flyway + `JdbcClient` (no JPA/ORM). Purchase orders, lines, lifecycle
  events, and the supplier outbox live in Postgres; submit updates the PO and enqueues the outbox in
  one transaction. Local Compose runs PostgreSQL 16; JDBC repositories are tested with Testcontainers.
  ADR 0015.
- **Supplier outbox:** `supplier_transmissions` with `FOR UPDATE SKIP LOCKED` claiming, bounded
  backoff/jitter retries, `SENT`/`ACKED`/`FAILED`, admin retry/ack endpoints, and a local
  `SupplierGateway` demo adapter. Real EDI/API transmission is still deferred.
- **Split API keys:** optional `ADMIN_API_KEY`; when set, `API_KEY` carries read-only scopes and the
  admin key carries `ROLE_API`. Production API-key auth requires a distinct admin key.
- **Micro-batched ledger sink:** concurrent movement writes coalesce into one array-parameter
  `MERGE` (`RETAIL_LEDGER_BATCH_*`), with per-row fallback if a batch fails. This removes the
  one-BigQuery-job-per-event bottleneck while preserving idempotency and ack-after-commit.
- **Structured JSON logging:** Logback `LoggingEventCompositeJsonEncoder` with `timestamp`, `level`,
  `logger`, `thread`, `message`, `service`, and allow-listed MDC (`requestId`, `clientId`, `jobId`,
  `traceId`, `spanId`) behind a bounded async appender (`LOG_ASYNC_QUEUE_SIZE`).
- **Distributed tracing:** Micrometer Tracing + OpenTelemetry with W3C `traceparent` injected into
  broker attributes and extracted as the consumer span parent; `trace_id`/`span_id` BigQuery job
  labels; OTLP/HTTP export with configurable sampling and a clean `TRACING_ENABLED=false` off switch.
- **Paginated list endpoints:** offset-token retail, replenishment, purchase-order, dead-letter, and
  supplier lists return `PageResponse` with an opaque `pageToken`/`nextPageToken`; malformed tokens
  are `400 INVALID_REQUEST` and there is no silent truncation. `/retail/inventory` and
  `/retail/replenishment` are the exception: ranked top-N reads return `truncated` and no
  continuation token. Catalog and job results forward BigQuery provider tokens.
- **Shared SQL partials:** `RetailSqlCatalog` expands `{{include:name}}` partials with cycle/depth
  checks, so the inventory preamble and recommendation formula have one source of truth.
- **Ledger APIs:** `GET /api/v1/retail/inventory/movements` and
  `GET /api/v1/retail/inventory/reconciliation` (drift report with tolerance, limit, truncation).
- Error code `INVALID_EVENT` (400) for semantic retail-event validation at intake.
- **Event metrics:** processed/duplicate, retry exhaustion, sink failures, DLQ, movements read, and
  reconciliation drift counters.
- **Architecture tests:** ArchUnit rules for module boundaries, domain independence, and cycle-free
  retail slices; module map at `docs/architecture/modules.md`.
- Message publishing failure maps to `503 SERVICE_UNAVAILABLE`; exhausted consumer retries route to
  the configured dead-letter topic.
- BigQuery migration policy in `db/README.md`; owned DDL is applied by `make retail-schema`, never
  on app startup.
- Spring Security 7: stateless API key (local) and OIDC/JWT resource server (production), scoped
  authorization, fail-closed startup.
- Request IDs (`X-Request-Id`) propagated to headers, ProblemDetail, MDC logs, and BigQuery labels.
- Structured access logs and request-scoped MDC (`requestId`, `clientId`, `jobId`).
- Pagination envelopes for catalog endpoints and `GET /api/v1/jobs/{jobId}/results`.
- Request-body, SQL-length, parameter-count, and parameter-value limits.
- CI workflow, Dependabot, SECURITY.md, CONTRIBUTING.md, AGENTS.md, ADR 0001–0006.
- Typed BigQuery result conversion, nested schema, duplicate-column disambiguation, RANGE output.
- Typed parameter binder with aliases, duplicate detection, NUMERIC precision, BYTES validation.
- Readiness group includes the BigQuery indicator.
- Comment/string-aware SQL statement separator check.
- Sanitized BigQuery health-probe failures.

### Changed

- **Confirmation guardrails:** `deploy.sh` requires a typed `DEPLOY` (or `CONFIRM_DEPLOY=yes`),
  chaos scenarios require `CHAOS`/`CONFIRM_CHAOS`, demo seeding requires `CREATE`/`CONFIRM_SEED`, and
  `teardown.sh` keeps its `CONFIRM=yes` gate. Redeploys reuse existing API keys and the database
  password; rotate explicitly.
- **One retail read path:** all retail adapters now call `RetailSqlExecutor`; only `retail.query`
  builds a `QueryRequest`. Retail reads use their own BigQuery slot pool and label jobs
  `surface=retail`, so operator `/query` traffic cannot starve them.
- **Honest error taxonomy:** business-rule violations return `409 BUSINESS_RULE_VIOLATION`, unique
  constraint violations return `409 CONFLICT`, and a missing dataset returns `503
  DATASET_NOT_PROVISIONED` (non-retryable) instead of a retryable `DATA_NOT_READY`.
- **Unknown stores are 404** for inventory, replenishment, reconciliation, and run creation.
- **Shared job labels:** one `JobLabels` builder normalises `request_id` identically on every
  surface; request ids with dots or dashes no longer produce two different label values.
- **Bounded batch memory:** `RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES` (32 MiB default) rejects with
  `LEDGER_OVERLOAD` before the heap is at risk; `size > maxPending` fails startup validation.
- **Response budget honesty:** a first row that alone exceeds `MAX_RESPONSE_BYTES` is dropped with
  `truncated=true` instead of being returned whole.
- **Per-page schema work:** duplicate column names are computed once per page (O(columns)) instead of
  once per row (O(columns²)).
- **Single SQL column source:** the replenishment candidate pipeline, proposal/run projections, and
  inventory preamble are shared partials with drift tests.
- **Observability and module boundaries:** JSON logs replace the text pattern; `TraceContext`,
  `TraceLabels`, and `QueryResultMapper`/`CatalogService` split orchestration from mapping; purchase
  owns its approved-proposal and run ports; `messaging` no longer owns retail envelopes.
- **Batch sink safety:** shutdown completes every accepted caller, retryable outages fail the batch
  instead of fanning out per row, the batch MERGE dedupes repeated `event_id`s, and batch metrics
  have a single owner.
- **Honest retry semantics:** retryable sink exhaustion now nacks for broker redelivery instead of
  forcing an operator DLQ replay; `.github/workflows/ci.yml` starts PostgreSQL and smoke-tests an
  authenticated control-plane read.
- **Fail-closed edges:** Postgres failures map to `503 SERVICE_UNAVAILABLE`; the pinned OpenAPI
  contract requires authentication; repeated `getInputStream()` calls cannot reset the chunked-body
  budget; `MAX_RESPONSE_BYTES` has a startup ceiling.

### Fixed

- A BigQuery dry-run health probe no longer marks readiness DOWN when the job has no id; health
  details reject null values.
- BigQuery timestamp bindings truncate Java nanoseconds to microseconds; `TIMESTAMP(@value)` rejects
  nine-digit fractional seconds.
- The demo seed adds the required `snapshot_date` partition predicate and uses `WHERE TRUE` for the
  projection `DELETE`, so a fresh BigQuery dataset seeds without manual SQL edits.
- The Dockerfile no longer uses BuildKit-only cache mounts, so Cloud Build's legacy builder and
  plain `docker build` both work.
- `make retail-seed` loads data without re-applying schema DDL, avoiding BigQuery table-update rate
  limits on repeated seeds.
- Consumer trace scopes restore MDC captured before the span becomes current, so correlation ids do
  not leak across requests when a Spring tracing decorator is installed.
- The batch ledger MERGE referenced its array parameters without the `@` prefix, so the statement
  could not be analysed and every batch silently degraded to per-row writes. A test now derives the
  parameter set from the rendered SQL and compares it with the bound map.
- The deduped ledger view used a different tie-break (`ingested_at` only) than the inline queries
  (`ingested_at, event_time`); batch writes share `ingested_at`, so the tie was reachable.
- `PageToken` no longer throws on a token it generated itself at the offset ceiling; it stops
  issuing tokens when the bounded window is exhausted.
- Query results truncated by the byte budget no longer return a `nextPageToken` that would skip the
  dropped rows.

- **Postgres control plane:** purchase orders, lines, lifecycle events, and the supplier outbox live
  in Postgres (Flyway + `JdbcClient`). `submit` updates the PO and inserts the outbox row in one
  transaction. The BigQuery PO repository and dual-runtime toggle were removed.
- **Control/retail boundary:** `control.supplier` owns the outbox and reader ports; the retail store
  adapts its PO model. `control` imports no `retail` package.
- **Supplier payload:** the outbox payload is a versioned control-owned contract with
  `transmissionId`, `schemaVersion`, and every PO line.
- **Transactional proposal decisions:** the proposal transition and its append-only decision are
  one BigQuery `BEGIN TRANSACTION` script with a run-status assertion.
- **Run safety:** a rejected re-run no longer marks the previous COMPLETED run FAILED; decisions
  acquire the same run lease and require a COMPLETED run; re-runs preserve decided proposals.
- **One PO per scope:** `purchase_orders` has a partial unique index on
  `(run_id, store_id, supplier_id)` for non-cancelled orders; a different client idempotency key
  cannot duplicate the same order, while a cancelled draft can be replaced. Line creation fails rather
  than silently truncating above 1,000 lines.
- **Chunked-body limit:** `MAX_REQUEST_BYTES` is enforced for undeclared/chunked bodies, not only
  `Content-Length`.
- **Writer guardrails:** `BIGQUERY_WRITER_MAX_BYTES_BILLED`, a writer semaphore, and `executeIdempotent`
  bound and classify controlled DML; zero-row idempotent merges do not fail on missing statistics.
- **Projection safety:** refresh holds a Postgres lease, rebuilds incomplete projections, removes
  stale rows, and starts from the oldest unprojected movement so late events are applied.
- **Business calendar:** `RETAIL_BUSINESS_ZONE` drives event business dates and all "today" guards.
- **Response budget:** `MAX_RESPONSE_BYTES` truncates rows with `truncated=true` instead of
  materialising an unbounded response.
- **Opening balances:** `OPENING_BALANCE` is accepted by event intake so a real ledger can be
  bootstrapped.
- **Broker safety:** the `prod`/`production` profile rejects the in-memory broker and demo dataset;
  `SUPPLIER_GATEWAY` is required explicitly.
- **Replenishment run leases:** `control_run_leases` serializes creation for one `runId` across
  instances; an expired lease is recoverable. `JdbcRunLease` is the Postgres adapter.
- **PO snapshot audit:** purchase-order lines store `source_proposal_version`, and lines are
  inserted in one JDBC batch. A PO is bounded to 1,000 lines.
- **OIDC `jwk-set-uri`:** OIDC can be configured with a JWK set instead of issuer discovery.
- **Wire DTOs moved out of `api`:** generic query records live in `model.query`, module records in
  `retail.*.dto` / `control.supplier.dto`, and `ApiVersion` in `contract`. The full package graph is
  cycle-free and ArchUnit forbids application/service → api edges.
- `FLYWAY_ENABLED` defaults to `false`; Compose sets it to `true` explicitly. `SUPPLIER_GATEWAY` has
  no default, so a fake local supplier cannot silently stand in for production.
- Upgraded to Spring Boot 4.1.2 / Spring Framework 7 / Jackson 3 / Tomcat 11 and BigQuery 2.76.0.
- Compose binds to 127.0.0.1 by default; container is read-only with dropped capabilities.
- SQL schema types are reported using standard names (`INT64`, `FLOAT64`, `BOOL`, `STRUCT`).
- `NUMERIC`/`BIGNUMERIC` results are strings to preserve precision; inputs must be strings.
- `SPRINGDOC_ENABLED` defaults to `false` in the base config; Compose/CI enable it explicitly.
- Unknown JSON properties are rejected to prevent silent typos.
- Upstream auth failures map to 502, rate limits to 429 with `Retry-After`.
- `make env` generates a 256-bit API key; `make key` rotates it and keeps the old key for overlap.

### Security

- ADC and service-account paths are ignored by Git and Docker.
- Maven wrapper distribution checksum pinned; Maven updated to 3.9.16.
- Liveness/readiness probes are public; other actuator endpoints require `ROLE_API` or
  `SCOPE_retail.admin`.
- OpenAPI/Swagger UI is public while enabled; set `SPRINGDOC_ENABLED=false` outside local use.
- API key is required unless OIDC is configured; startup fails closed otherwise.
- Workspace `hd` and service-account `sub` now grant only `SCOPE_retail.read`; operator/query access
  requires an explicit `roles` claim. `hd` is honored only for Google issuers.
- Unmatched routes are denied instead of accepting any authenticated token.
- The outbox claim carries its version into `SENT`/`PENDING`/`FAILED` transitions, so a stale worker
  cannot overwrite a newer claim. Operator retry resets the attempt counter.
- `AppSecurityProperties.toString()` redacts API key material.
- With the `prod` profile, `API_KEY` is rejected unless `ALLOW_API_KEY_IN_PRODUCTION=true`; OIDC is
  the expected production identity.
- `SupplierTransientException` is now an explicit transient classification; other runtime supplier
  failures remain transient, and unclassified adapter bugs still surface in logs.

## [0.1.0] - 2026-11-09

### Added

- Initial Spring Boot REST API over BigQuery with query, dry-run, catalog, health, and Prometheus
  endpoints.
- Multi-stage Docker image, Compose stack with ADC mount, Makefile, README.
