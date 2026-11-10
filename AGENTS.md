# AGENTS.md

Canonical agent instructions for this repository. Agent tools that read `AGENTS.md` should use this
file directly. Do not duplicate it in tool-specific files; add an adapter only if a tool cannot read
this file, and make the adapter a pointer.

## Read first

- `README.md` — setup, endpoints, configuration, troubleshooting.
- `CONTRIBUTING.md` — human workflow, code expectations, version policy, done criteria.
- `docs/adr/` — binding platform/auth/contract/gateway decisions.
- `docs/api/errors.md` — stable error codes and retry semantics.
- `docs/deployment/gcp.md` — hosted demo path, cost guardrails, and production differences.

## Project orientation

- Java 21 / Spring Boot 4.1 REST adapter over Google BigQuery.
- Single Maven module. **Postgres is the OLTP control plane** (purchase orders, lifecycle events,
  supplier outbox); **BigQuery is the OLAP analytical plane** (ledger, projections, retail reads).
  No JPA/ORM: Postgres uses Flyway + `JdbcClient` with explicit SQL.
- `/api/v1` is the wire contract; OpenAPI 3.1 is committed and drift-tested.
- Errors are RFC 9457 `application/problem+json` with a machine-readable `code`.

## Commands

- `make test` — unit, MVC, security, contract, and filter tests; no GCP calls.
- `make verify` or `./mvnw -B -ntp verify` — required gate before done.
- `make openapi` — regenerate `src/main/resources/static/openapi/springtime-v1.json`; review and
  commit the diff.
- `make openapi-check` — fail if the committed contract drifts from code.
- `make it` — opt-in real-BigQuery integration tests; requires ADC; do not run by default.
- `make run`, `make up`, `make logs`, `make env`, `make key`.
- `make versions` — dependency/plugin updates.
- `target/` is generated; never edit it.

## Architecture map

- Request path: `web/` filters → `api/` controllers → application services → ports/adapters →
  BigQuery or Postgres.
- `control/`: Postgres control plane; `control/supplier` owns the durable outbox, worker, and
  `SupplierGateway`/reader ports; `control/run` owns the replenishment run lease. It imports no
  `retail` package; retail adapts to its ports.
- Feature modules (`retail.*`, `control.*`) own their controllers, wire DTOs, application services,
  ports, and adapters. Imports point inward; `web`, `security`, and `config` never import `retail` or
  `control`, and application/services never import an `api` package.
- Wire records live outside controllers: `model/query` for the generic surface, `retail/*/dto` and
  `control/supplier/dto` for module contracts, `contract/` for the API version.
- `config/`: properties, `ConfigurationRules`, fail-fast `STARTUP_CONFIG_INVALID` before the server
  listens.
- `security/`: stateless Spring Security; API key or OIDC/JWT; no Basic, sessions, or CSRF.
- `schema/`: deterministic `schemaHash`.
- `api/ApiExceptionHandler` + `web/ErrorCode` → RFC 9457 problem responses.
- `OpenApiContractTest` enforces the committed OpenAPI contract.

## Invariants — do not break

- API change ⇒ `make openapi`, review/commit the JSON. Breaking change ⇒ `/api/v2`.
- New/changed error code ⇒ update `docs/api/errors.md` and `CHANGELOG.md`.
- Auth is stateless and fail-closed. Never weaken, disable, or bypass it.
- **Migrations:** do not run migrations on app startup. Postgres uses Flyway; follow
  `docs/adr/0005-bigquery-schema-migrations.md` for BigQuery and do not add Flyway/Liquibase there.
- Read-only guard: one statement; only `SELECT`/`WITH`/`EXPLAIN`/`DESCRIBE`/`SHOW`. Dry runs skip
  the guard because they execute nothing.
- Limits clamp down. Callers cannot raise byte, row, page, concurrency, or cache limits.
- `BigQueryGateway` (read) and `BigQueryDmlGateway` (controlled writes) are the SDK boundaries. Do
  not add JPA/ORM or call the SDK from controllers.
- Table schemas come from live BigQuery; keep `schemaHash` deterministic.
- Validate `X-Request-Id`; propagate to header, MDC, problem detail, and BigQuery job labels.
- Unknown JSON request fields are rejected intentionally. Do not relax this.
- Every list endpoint returns an envelope, never a bare array. Offset-paginated endpoints use
  `PageResponse<T>` with an opaque `pageToken` (malformed → `400 INVALID_REQUEST`) and a
  `nextPageToken` only when another page exists. `/retail/inventory` and `/retail/replenishment` are
  ranked top-N reads that return `truncated` and have no continuation token; reconciliation returns a
  bounded report with `truncated`. Catalog/table/job `pageToken` values are BigQuery provider tokens
  forwarded unchanged.
- Page caps: retail reads are capped by `RETAIL_MAX_PAGE_SIZE` (default 200); dead letters, runs,
  purchase orders/history, and supplier transmissions 200; PO lines 1,000; proposals 5,000.
  `RETAIL_DEFAULT_PAGE_SIZE` is the default for offset-paginated lists (PO lines default to 200).
- Error taxonomy: version/idempotency/state conflicts and unique-constraint violations are
  `409 CONFLICT`; policy refusals are `409 BUSINESS_RULE_VIOLATION`. A retail read against missing
  owned schema is `503 DATASET_NOT_PROVISIONED` (never retry); missing snapshot/ledger data is
  `503 DATA_NOT_READY` (retry), while an existing store with no rows is an empty page or `BALANCED`.
- Retail SQL lives under `sql/retail`; shared CTEs belong in `sql/retail/partials` and are pulled in
  with `{{include:name}}` through `RetailSqlCatalog` (nesting ≤ `MAX_INCLUDE_DEPTH = 2`). Do not copy
  a formula into a second query.
- Every retail read goes through `RetailSqlExecutor` (own slot pool, jobs labeled `surface=retail`);
  only `retail.query` may construct a `QueryRequest`. Unknown stores are `404`, not an empty page or
  "balanced".
- SLOs, failure modes, and runbooks live in `docs/operations/README.md`; alert rules live in
  `ops/prometheus/springtime-alerts.yml`. Renaming a metric requires updating the alerts file —
  `AlertRulesMetricContractTest` fails the build otherwise.
- `make obs-up`, `make loadtest`, and `make chaos` are the operational verification loop. CI
  validates the alert rules, dashboard JSON, Compose overlay, and k6 scripts.
- Logs are JSON on stdout with allow-listed MDC (`requestId`, `clientId`, `jobId`, `traceId`,
  `spanId`). Propagate W3C trace context through broker attributes and BigQuery `trace_id`/`span_id`
  labels; `JobLabels` is the single label builder and normalises `request_id`. Projection freshness is
  the gauge `retail.inventory.projection.last.success.timestamp.seconds`. Never log secrets or
  request bodies.
- Do not hand-edit generated OpenAPI JSON.

## Database and migrations

- **Postgres is the control plane.** It owns purchase orders, lines, lifecycle events, and the
  supplier outbox. Use Flyway migrations and explicit `JdbcClient` SQL. No JPA/Hibernate.
  Replenishment run/proposal state remains in BigQuery for now.
- **BigQuery is the analytical plane.** It owns the inventory ledger, projections, demand
  aggregates, and read models. Do not use BigQuery as a queue, lock manager, or multi-statement
  transactional store.
- Flyway migrations live in `src/main/resources/db/migration`. They are forward-only and immutable
  once applied. Local Compose may run them on startup; production runs them as a separate deploy
  job, never on app startup.
- BigQuery DDL is applied idempotently by `make retail-schema`; ADR 0005's `springtime_meta`
  ledger/checksum runner is not implemented yet. Keep DDL forward-only/expand-contract and snapshot
  before destructive changes.
- Analytical transformations/joins belong in Dataform or dbt later, not in the Java request path.

## Async events and long-running work

- Do not put event ingestion, BigQuery writes, or long transforms on the query/catalog hot path.
- Use Pub/Sub as durable ingress; return `202` and acknowledge only after the sink commits.
- Cloud Run services for low-latency consumers; Cloud Run Jobs for long batch/backfill; Workflows
  for multi-step orchestration; Dataflow only when continuous/windowed volume justifies it.
- At-least-once is the default: idempotency by `event_id`, DLQ for poison messages, no per-event
  `INSERT` query jobs.
- See `docs/adr/0007-async-events-and-long-running-work.md` and
  `docs/architecture/async-events.md`.

## Retail domain

- `retail/` contains analytical reads, the append-only inventory ledger, and replenishment/
  purchase-order decision workflows; durable PO and outbox state live in Postgres. Do not add
  request-supplied SQL, dataset names, or table names.
- **Inventory is a ledger.** `inventory_movements` is the source of truth; on-hand is a projection.
  Never update a balance in place. Corrections are reversing movements.
- `retail.dataset` is owned by config and validated at startup. Fact and ledger reads must include
  a partition-date predicate and use the bounded page/byte limits. Dimension/control lookups
  (`stores`, runs, proposals, dead letters) are exempt but must stay `LIMIT`-bounded.
- `/api/v1/retail/**` requires `ROLE_API` or the relevant `SCOPE_retail.*` authority. Reconciliation,
  projection refresh, and dead-letter replay are `retail.admin`. Keep retail separate from the
  operator `/query` surface.
- KPI formulas live in `docs/retail/README.md`; update it with SQL changes.
- On-hand reads use `inventory_on_hand_daily`, a stable view over `inventory_on_hand_projection`.
  Refresh the projection through the job/endpoint; do not compute full history on the request path.
- Seed with `make retail-schema` then `make retail-seed`; never seed a shared/production dataset.
  Do not run seeds or migrations on app startup.
- Replenishment runs are durable decision records (`replenishment_runs` / `replenishment_proposals`).
  Re-running the same `runId` replaces that run's proposals unless a proposal has been decided.
- Proposal decisions use `expectedVersion` optimistic concurrency, the
  `PROPOSED`/`APPROVED`/`REJECTED`/`ORDERED` state machine, and append-only
  `replenishment_proposal_decisions`. Supplier EDI/API transmission and receiving are deferred.
- Purchase orders are drafts built from `APPROVED` proposals for one run/store/supplier. The
  idempotency key prevents duplicate POs; `DRAFT` can be submitted or cancelled with
  `expectedVersion`, with an append-only lifecycle audit. Real EDI/API transmission and receiving
  are deferred.
- Deferred: promotion lift endpoint, supplier scorecard, ML forecasting, multi-store run
  orchestration, supplier EDI/API transmission, receiving/invoice matching, moving replenishment
  proposal state to Postgres, multi-tenant scopes.

## Messaging and idempotency

- Event intake is async: validate, publish, return `202`; never write BigQuery inline.
- `eventId` is the idempotency key. The BigQuery sink uses `MERGE` on
  `(event_id, business_date)`; at-least-once is the contract.
- The consumer acknowledges only after the ledger write commits (or a structured dead-letter
  publish succeeds). Transient failures nack; permanent failures go to the DLQ immediately.
- Retry only transient failures with bounded exponential backoff + jitter. Do not retry permanent
  validation or schema failures.
- The ledger sink micro-batches concurrent events. Batch outcomes are batch-level (`INSERTED` for
  every caller if any row inserted); retryable failures fail the whole batch and nack; deterministic
  row failures use a bounded per-row fallback. `RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES` bounds retained
  payloads and fails closed with `LEDGER_OVERLOAD`.
- The local default is a bounded in-memory broker with a local relay; it fails closed when full and
  is not durable. Production uses Pub/Sub with a subscription DLQ and `PubSubRetailEventSubscriber`.
- Dead letters are recorded in `retail_event_dead_letters` (or the bounded local store). Replay is
  operator-triggered, admin-only, and safe to retry because the ledger dedupes by `event_id`.
- See `docs/messaging/README.md`, `docs/adr/0009-retail-event-intake.md`,
  `docs/adr/0010-inventory-ledger-and-idempotent-sink.md`, and
  `docs/adr/0011-dlq-replay-and-incremental-projection.md`.
- Do not claim exactly-once delivery; ledger dedupe is part of the contract.

## Conventions

- Constructor injection; records for DTOs; validate at the HTTP edge; no field injection.
- Keep BigQuery SDK types out of controllers.
- Use Boot 4 modular starters and Jackson 3 APIs; do not paste Boot 3/Jackson 2 examples.
- Add a test for every behavior change. Prefer unit/MVC tests; real-GCP tests are opt-in.
- Conventional Commits preferred. Follow `CONTRIBUTING.md` for PRs.

## Change recipes

- New endpoint: controller + DTO → service → gateway if needed → MVC/security tests →
  `make openapi` → update error catalog if codes changed → `make verify`.
- New config: properties record + validation rule + startup test + `application.yml` + README table.
- Dependency change: BOM-managed vs explicitly pinned; dedicated PR; `make versions`.

## Security and secrets

- Never read, print, or commit `.env`, ADC files, `secrets/`, tokens, or keys.
- Project-specific deployment notes, real IDs, keys, logs, and command history belong in `.local/`
  (gitignored and dockerignored). Never copy them into committed docs or `.env.example`.
- Never log SQL text, row data, credentials, or unsanitized upstream errors.
- Keep Compose loopback-only and read-only; do not publish `0.0.0.0`.
- IAM is the real data boundary; the SQL keyword guard is convenience, not a parser.
- Keep springdoc/actuator protected or disabled outside local; docs default off in the base config.

## Known gaps

- No automatic DLQ replay policy; replay is operator-triggered and admin-only.
- No query-submission idempotency key or per-key rate limit yet. Response rows are byte-budgeted
  (`MAX_RESPONSE_BYTES`) and set `truncated=true`.
- The local in-memory broker and dead-letter store are non-durable and intended for tests/demos.
- The on-hand projection is refreshed by an opt-in scheduler; it is not synchronous with events.
- The deduped ledger view is retained for manual loads; hot paths inline dedupe after the partition
  predicate, but `inventory_reconciliation` is still cumulative over ledger history.
- Replenishment runs are bounded single-store/in-process; multi-store runs need a job orchestrator.
- Replenishment run creation is a multi-job BigQuery sequence serialized by a Postgres run lease;
  the BigQuery writes themselves are not one transaction.
- Do not describe the service as production-ready for untrusted clients.
- BigQuery gateway still uses SDK types in its signatures; a neutral model is a later refactor.

## Definition of done

- `make verify` green; container builds; contract/docs/CHANGELOG updated; no secrets.
