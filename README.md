# springtime

A **Java 21 / Spring Boot 4** REST service over **Google BigQuery**, packaged as a multi-stage Docker
image and runnable with `docker compose up`.

It uses **Postgres for the control plane** (purchase orders, lifecycle events, supplier outbox) and
**BigQuery for the analytical plane** (inventory ledger, projections, demand, retail reads). No ORM:
Postgres access is explicit SQL through `JdbcClient`, with Flyway migrations. BigQuery is reached
through typed gateway ports.

## What it does

Springtime is a demo of a store replenishment workflow:

1. A planner picks a store and sees which SKUs are below cover.
2. The service ranks what to reorder using demand, lead time, safety stock, and case packs.
3. Approved recommendations become purchase orders and a supplier outbox entry.

The dashboard is a read-only window into that flow; Swagger is the full API. Data is synthetic.
The architecture and operations around it are real, but this is a portfolio demo, not a production
planning system.

For a guided review, start with [docs/showcase.md](docs/showcase.md): a read-only tour, the
operator walkthrough, the Swagger UI hand-off, and what a reviewer's agent can exercise.

## Stack

| Piece | Version / choice |
| --- | --- |
| Runtime | Java 21 LTS (virtual threads enabled) |
| Framework | Spring Boot 4.1.2, Spring Framework 7, Jackson 3, Tomcat 11 |
| BigQuery | `google-cloud-bigquery` 2.76.0 |
| Control plane | PostgreSQL 16, Flyway, Spring `JdbcClient` (no JPA/ORM) |
| API docs | springdoc-openapi 3.2.0 (Swagger UI) |
| API contract | OpenAPI 3.1 JSON, committed and drift-tested |
| Data access | `BigQueryGateway` read port, `BigQueryDmlGateway` write port, explicit JDBC SQL |
| Security | Spring Security 7: API key (local) or OIDC/JWT resource server (production) |
| Traffic identity | `X-Request-Id` -> MDC -> access logs + BigQuery `request_id` label |
| Build | Maven 3.9.16 (wrapper with SHA-256 pin) |
| Ops | Actuator health probes, Micrometer 1.17, Prometheus |
| Container | Multi-stage, Temurin 21 Alpine JRE, non-root, read-only rootfs |
| Automation | GitHub Actions CI, Dependabot |

Spring Boot 3.5 reached OSS end-of-life on 2026-06-30; this project starts on the supported 4.1 line.
See [ADR 0001](docs/adr/0001-platform-baseline.md) for the rationale.

## Features

- **Retail copilot v1:** stores, inventory risk, replenishment recommendations, and a store
  dashboard over a partitioned BigQuery retail model. See [docs/retail](docs/retail/README.md).
- **Inventory movement ledger:** append-only `inventory_movements` table on BigQuery; `event_id` is
  the idempotency key and the sink MERGEs on `(event_id, business_date)`. On-hand is a projection, not
  an updated balance. `GET /api/v1/retail/inventory/movements` exposes the ledger.
- **Idempotent event pipeline:** `POST /api/v1/retail/events` validates and publishes; a Pub/Sub
  subscriber (or local relay) translates the event, writes a signed movement with a `MERGE` on
  `(event_id, business_date)`, acknowledges only after the write, and routes permanent/exhausted
  failures to a structured dead-letter envelope. See [docs/messaging](docs/messaging/README.md).
- **Ledger reconciliation:** `GET /api/v1/retail/inventory/reconciliation` compares the ledger
  projection with the inventory snapshot and reports drift by store/product.
- **Dead-letter replay:** `GET /api/v1/retail/events/dead-letters` lists durable failures;
  `POST /api/v1/retail/events/dead-letters/{eventId}/replay` republishes the original event. The
  ledger `MERGE` makes replay safe even if the first attempt partially succeeded.
- **Incremental on-hand projection:** `inventory_on_hand_projection` is the maintained read model;
  `POST /api/v1/retail/inventory/projection` runs an incremental or full refresh, and an optional
  scheduler (`RETAIL_PROJECTION_ENABLED=true`) keeps it fresh. Late events are corrected by a
  configurable lookback window.
- **Replenishment runs and proposals:** `POST /api/v1/retail/replenishment-runs` creates a durable,
  idempotent single-store decision run and persists prioritized proposals; `GET
  /api/v1/retail/replenishment-runs/{runId}/proposals` reads them. Re-running the same `runId`
  replaces that run's proposals only while every proposal is still `PROPOSED`; once a decision
  exists the request is `409` and needs a new `runId`.
- **Proposal decisions:** `POST .../proposals/{productId}/approve|reject|order` moves a proposal
  through `PROPOSED` → `APPROVED`/`REJECTED` → `ORDERED`, with `expectedVersion` optimistic
  concurrency and an append-only decision history at `GET .../history`.
- **Purchase orders:** `POST /api/v1/retail/purchase-orders` creates a draft PO from approved
  proposals for one run/store/supplier, with an idempotency key, copied lines, totals, and a
  lifecycle audit. `submit`/`cancel` are draft-only and version-guarded.
- **Durable supplier outbox:** `submit` updates the Postgres PO and inserts the outbox row in one
  transaction. A worker claims rows with `FOR UPDATE SKIP LOCKED`, calls a `SupplierGateway`, and
  records `SENT`/`ACKED`/`FAILED`. `POST /purchase-orders/{poId}/transmit` remains an idempotent
  manual trigger. The local gateway is a demo adapter, not a real EDI/API transmission.
- `POST /api/v1/query` — named parameters, dry runs, row limit, per-query byte ceiling, job timeout.
- **Typed results:** `INT64`/`FLOAT64`/`BOOL` are JSON numbers/booleans, `TIMESTAMP` is RFC 3339,
  `BYTES` is base64, `NUMERIC`/`BIGNUMERIC` are strings (precision-safe), and
  `RECORD`/`REPEATED`/`RANGE` become nested JSON.
- Duplicate result columns are disambiguated (`x`, `x_2`) in both rows and schema.
- Read-only guard: `SELECT`, `WITH`, `EXPLAIN`, `DESCRIBE`, `SHOW`; one statement per request.
- Concurrency cap with `429` + `Retry-After`; server-side job timeout; a caller can enable caching
  but cannot disable a server-enabled cache.
- Catalog browsing with `pageToken`/`pageSize` envelopes for datasets and tables. Job pagination via
  `GET /api/v1/jobs/{jobId}/results`. Offset-paginated retail lists use the same envelope; the ranked
  `/retail/inventory` and `/retail/replenishment` reads are top-N and return `truncated` instead of a
  continuation token.
- **Pinned API contract:** OpenAPI 3.1 JSON committed at
  `src/main/resources/static/openapi/springtime-v1.json`, served at `/openapi/springtime-v1.json`
  behind authentication,
  and enforced by a drift test (`make openapi` / `make openapi-check`).
- **Stable error protocol:** RFC 9457 `application/problem+json` with a machine-readable `code`,
  `requestId`, `retryable`, optional `errors`/`jobId`/`reason`, and a published catalog at
  [docs/api/errors.md](docs/api/errors.md).
- **Fail-fast startup:** cross-field configuration validation with environment-variable names and a
  readable `STARTUP_CONFIG_INVALID` failure block before the server listens.
- **BigQuery resource gateway:** services depend on `BigQueryGateway` for reads and the narrow
  `BigQueryDmlGateway` for controlled writes (ledger sink, dead-letter persistence/replay,
  replenishment run/proposal writes and decisions, projection refresh); one schema/row mapping path
  and a deterministic `schemaHash` for table schemas.
- **Strict parameter types:** the `ParameterType` enum rejects unknown types at the API boundary and
  appears as an enum in OpenAPI.
- **Auth:** stateless Spring Security with a constant-time API key for local use (with
  `API_KEY_PREVIOUS` rotation), or OIDC/JWT bearer tokens for production. Authorities are
  `SCOPE_bigquery.query` / `SCOPE_bigquery.read` for the operator surface and `SCOPE_retail.read` /
  `SCOPE_retail.write` / `SCOPE_retail.admin` for retail. A roles claim maps to any `SCOPE_*`; a
  Google `hd` or service-account `sub` grants only `SCOPE_retail.read`. When `ADMIN_API_KEY` is set, `API_KEY` carries read-only scopes and
  `ADMIN_API_KEY` carries operator authority. With `SPRING_PROFILES_ACTIVE=prod` (or `production`),
  a configured API key is rejected unless `ALLOW_API_KEY_IN_PRODUCTION=true`; API-key auth in
  production then requires a distinct `ADMIN_API_KEY`. No Basic auth, no sessions, no CSRF tokens;
  startup fails if neither mechanism is configured.
- **Traffic identity:** validated `X-Request-Id` (or generated UUID) in the response header, logs,
  problem details, and BigQuery job labels.
- **Paginated lists:** offset-paginated endpoints return
  `{"items": [...], "nextPageToken": ..., "pageSize": ...}`; `pageToken` is an opaque base64url
  offset, bounded to 512 characters and an offset ceiling, validated locally, and never
  interpolated into SQL. `/retail/inventory` and `/retail/replenishment` are top-N reads returning
  `{"items", "asOfDate", "pageSize", "truncated"}` with **no** continuation token; when
  `truncated=true`, narrow the filters or raise `pageSize` within the cap. Reconciliation returns a
  bounded report with `truncated`. Catalog and job results forward BigQuery provider tokens
  unchanged, so an invalid one is `400 UPSTREAM_REJECTED`.
- **JSON logs:** one Logstash-encoded JSON object per line with `timestamp`, `level`, `logger`,
  `thread`, `message`, `service`, and the `requestId`/`clientId`/`jobId`/`traceId`/`spanId` MDC
  fields, through a bounded async appender.
- **Distributed tracing:** Micrometer/OpenTelemetry with W3C `traceparent` propagation over the
  broker and `trace_id`/`span_id` BigQuery job labels. OTLP/HTTP export is asynchronous and
  optional (`TRACING_ENABLED=false` disables it).
- **Telemetry:** structured access logs (method, path, route, status, latency, client, request id),
  MDC `requestId`/`clientId`/`jobId`, Prometheus metrics, build info, and
  event/ledger/projection counters.
- **Executable architecture:** ArchUnit tests enforce module boundaries and cycle-free retail
  slices; see [docs/architecture/modules.md](docs/architecture/modules.md).
- **Defensive limits:** request-body bytes, SQL length, parameter count/length, row cap,
  concurrency cap, query timeout, and byte ceiling — all configurable.
- Liveness/readiness probes; readiness includes Postgres and a cached BigQuery dry-run indicator.
- OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui/index.html`.
- Prometheus metrics at `/actuator/prometheus`, build info at `/actuator/info`.

## Layout

For a one-page information-flow overview, see
[docs/architecture/flow.md](docs/architecture/flow.md).

```
.
├── compose.yaml                      # loopback-only local stack
├── Dockerfile                        # multi-stage, non-root, read-only runtime
├── pom.xml
├── .env.example
├── .github/
│   ├── workflows/ci.yml              # verify + container build + smoke test
│   └── dependabot.yml                # Maven, Docker, Actions updates
├── ops/
│   ├── prometheus/springtime-alerts.yml  # SLO alert rules (kept in sync by a test)
│   ├── observability/                # Prometheus/Tempo/Loki/Grafana/Alloy/Alertmanager
│   ├── loadtest/                     # k6 scenarios with SLO thresholds
│   └── chaos/                        # local failure-injection scripts
├── deploy/gcp/                       # Cloud Run + Cloud SQL + BigQuery deploy/teardown
├── docs/
│   ├── operations/README.md          # SLOs, failure modes, runbooks, capacity/cost
│   ├── deployment/gcp.md             # Cloud Run deployment path + monthly cost model
│   ├── adr/0001-platform-baseline.md
│   ├── adr/0002-auth-traffic-pagination-limits.md
│   ├── adr/0003-api-contract-versioning-error-protocol.md
│   ├── adr/0004-bigquery-gateway-and-schema-source.md
│   ├── adr/0005-bigquery-schema-migrations.md
│   ├── adr/0006-google-authentication.md
│   ├── adr/0007-async-events-and-long-running-work.md
│   ├── adr/0008-retail-inventory-copilot.md
│   ├── adr/0009-retail-event-intake.md
│   ├── adr/0010-inventory-ledger-and-idempotent-sink.md
│   ├── adr/0011-dlq-replay-and-incremental-projection.md
│   ├── adr/0012-replenishment-runs-and-proposals.md
│   ├── adr/0013-proposal-decisions.md
│   ├── adr/0014-purchase-orders.md
│   ├── adr/0015-postgres-control-plane-and-supplier-outbox.md
│   ├── api/errors.md
│   ├── architecture/async-events.md
│   ├── architecture/flow.md
│   ├── architecture/modules.md
│   ├── auth/README.md
│   ├── messaging/README.md
│   ├── retail/README.md
│   └── reviews/...
├── db/README.md                     # BigQuery + Postgres migration policy
├── db/retail/                       # retail schema + deterministic demo seed
│   ├── schema/                      # forward-only ledger DDL and read-model views
│   └── seed/                        # synthetic dims/facts/ledger bridge
├── AGENTS.md                        # canonical coding-agent instructions
├── src/main/java/com/springtime/bigquery
│   ├── api/                          # generic query/catalog controllers
│   ├── config/                       # properties, BigQuery bean, OpenAPI, startup validation
│   ├── contract/                     # API version constants
│   ├── control/                      # Postgres control plane: supplier outbox, run lease
│   ├── gateway/                      # BigQuery read port and narrow controlled-DML port
│   ├── health/                       # BigQuery health indicator
│   ├── messaging/                    # broker port, in-memory/Pub/Sub, retry primitives
│   ├── model/                        # shared query model + generic wire records
│   ├── retail/                       # retail modular monolith
│   │   ├── api/                      # read endpoints
│   │   ├── event/                    # intake, translator, processor, consumers, DLQ/replay
│   │   ├── inventory/                # ledger, sink, projection, reconciliation, read adapter
│   │   ├── model/                    # retail read models
│   │   ├── purchase/                 # purchase orders, lines, lifecycle audit
│   │   ├── query/                    # fixed-SQL executor and retail read port
│   │   ├── replenishment/            # runs, proposals, decision state machine, API
│   │   └── service/                  # retail application service
│   ├── schema/                       # deterministic schema hash
│   ├── security/                     # Spring Security chain + API key filter
│   ├── service/                      # query execution, SQL guard, parameter binder, row codec
│   ├── observability/                # trace context, BigQuery trace labels
│   ├── util/                         # small pure helpers (log sanitization, backoff, ids)
│   └── web/                          # request id, version, access log, size limit, problem factory
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/                 # Postgres Flyway migrations
│   ├── logback-spring.xml            # JSON logs + bounded async appender
│   ├── META-INF/spring.factories     # startup failure analyzer
│   ├── sql/retail/                   # fixed read SQL + ledger MERGE
│   └── static/openapi/springtime-v1.json
└── src/test/java/...                 # unit, MVC, security, contract, architecture, opt-in integration tests
```

## Prerequisites

- Docker with Compose v2. Compose starts PostgreSQL 16 for the control plane.
- [gcloud CLI](https://cloud.google.com/sdk/docs/install) for ADC
- A GCP project with the BigQuery API enabled
- JDK 21 for bare local runs

Postgres is **required at runtime**. `make up` starts it and runs Flyway. A bare `make run` needs a
reachable Postgres via `DATABASE_URL` that is already migrated; set `FLYWAY_ENABLED=true` to let
that run apply migrations locally. This is deliberate so development matches production, where
migrations run as a separate deploy job.

## 1. Authenticate

```bash
gcloud auth application-default login
gcloud config set project YOUR_PROJECT_ID
gcloud services enable bigquery.googleapis.com
```

The stack does **not** use a service-account JSON file by default. It mounts your host ADC directory
(`~/.config/gcloud`) read-only into the container at `/home/app/.config/gcloud`, so no secrets are
baked into the image.

### IAM

Grant the identity running the app:

- `roles/bigquery.jobUser` on the project (create/run jobs)
- `roles/bigquery.dataViewer` on the datasets it may read
- `roles/bigquery.metadataViewer` if you only need catalog browsing

For anything shared, prefer a dedicated service account with exactly those roles rather than your
personal ADC. Production should use Workload Identity, not a mounted key.

## 2. Configure

```bash
make env
# copies .env.example and generates an API_KEY; it does not set GOOGLE_CLOUD_PROJECT
```

Compose reads `.env` automatically. At minimum set `GOOGLE_CLOUD_PROJECT`. The app fails fast at
startup if the project id or an authentication mechanism is missing. Rotate the local key with
`make key`.

## 3. Run

```bash
make up        # or: docker compose up --build
```

The port is bound to **127.0.0.1** by default. Change the `ports` entry in `compose.yaml`
deliberately if you need LAN access.

```bash
KEY=$(grep '^API_KEY=' .env | cut -d= -f2)
curl -s localhost:8080/actuator/health/liveness
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v1/datasets | jq
open http://localhost:8080/swagger-ui/index.html
```

OpenAPI/Swagger UI is public while enabled; the pinned `/openapi/**` contract requires operator
authority. Set `SPRINGDOC_ENABLED=false` in any shared environment.
All other endpoints (except liveness/readiness and docs) require authentication.

### Run without Docker

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
./mvnw spring-boot:run
# or: make run   (loads .env automatically)
```

If you use a service-account key locally:

```bash
export GOOGLE_APPLICATION_CREDENTIALS=$HOME/keys/springtime.json
```

## 4. Use the API

### Execute a query

```bash
curl -s localhost:8080/api/v1/query \
  -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT CURRENT_TIMESTAMP() AS now, 1 + 1 AS two, TRUE AS flag"}'
```

```json
{
  "jobId": "3b0f...",
  "schema": [
    {"name": "now", "type": "TIMESTAMP", "mode": "NULLABLE"},
    {"name": "two", "type": "INT64", "mode": "NULLABLE"},
    {"name": "flag", "type": "BOOL", "mode": "NULLABLE"}
  ],
  "rows": [{"now": "2026-11-09T21:00:00Z", "two": 2, "flag": true}],
  "returnedRows": 1,
  "totalRows": 1,
  "bytesProcessed": 0,
  "bytesBilled": 10485760,
  "cacheHit": false,
  "dryRun": false,
  "nextPageToken": null
}
```

The job id is also returned in the `X-BigQuery-Job-Id` response header, and the correlation id in
`X-Request-Id`. The response contains the first page of up to `maxResults` rows; `totalRows` reports
how many rows the query matched. When `nextPageToken` is non-null, fetch the next page:

```bash
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/jobs/3b0f.../results?pageToken=NEXT_TOKEN&pageSize=100' | jq
```

### Dry run (free cost estimate)

```bash
curl -s localhost:8080/api/v1/query \
  -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT * FROM `bigquery-public-data.usa_names.usa_1910_current`","dryRun":true}'
```

### Named parameters

```bash
curl -s localhost:8080/api/v1/query \
  -H "X-API-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{
        "sql": "SELECT @name AS name, @limit AS limit_value, @amount AS amount",
        "parameters": [
          {"name": "name",   "type": "STRING",  "value": "Ada"},
          {"name": "limit",  "type": "INT64",   "value": 10},
          {"name": "amount", "type": "NUMERIC", "value": "12345678901234567890.1234"}
        ]
      }'
```

Parameter rules:

- Types: `BOOL`, `INT64`, `FLOAT64`, `NUMERIC`, `BIGNUMERIC`, `STRING`, `BYTES`, `TIMESTAMP`, `DATE`,
  `TIME`, `DATETIME`, `GEOGRAPHY`, `JSON`, `INTERVAL`. Legacy aliases `INTEGER`, `FLOAT`, `BOOLEAN`
  are accepted.
- `NUMERIC`/`BIGNUMERIC` should be sent as **strings** to preserve precision.
- `BYTES` must be **base64**.
- `JSON` must be a JSON-encoded **string**; objects/arrays are rejected for scalar parameters.
- `RANGE` parameters are not supported; RANGE results are returned as `{start, end, type}`.
- Duplicate parameter names are rejected. Unknown JSON fields are rejected so a `dryrun` typo cannot
  silently become a billed query.
- Result `NUMERIC`/`BIGNUMERIC` values are returned as strings to avoid JavaScript precision loss
  above 2^53.

### Catalog

```bash
curl -s -H "X-API-Key: $KEY" 'localhost:8080/api/v1/datasets?pageSize=100' | jq
curl -s -H "X-API-Key: $KEY" 'localhost:8080/api/v1/datasets/my_dataset/tables?pageToken=NEXT' | jq
curl -s -H "X-API-Key: $KEY" localhost:8080/api/v1/datasets/my_dataset/tables/events/schema | jq
```

Catalog responses are envelopes:

```json
{"items": [...], "nextPageToken": "opaque-token-or-null", "pageSize": 100}
```

### Retail copilot

Apply the ledger schema and load the deterministic demo data once (requires `bq` + ADC):

```bash
make retail-schema
make retail-seed
```

Then:

```bash
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/dashboard?storeId=ST-001' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory?storeId=ST-001&riskLevel=CRITICAL' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/replenishment?storeId=ST-001' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/movements?storeId=ST-001&limit=20' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/reconciliation?asOfDate=2026-11-09' | jq
curl -s -X POST -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/projection?mode=INCREMENTAL' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/events/dead-letters' | jq
RUN_ID=$(curl -s -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  'localhost:8080/api/v1/retail/replenishment-runs' \
  -d '{"storeId":"ST-001","asOfDate":"2026-11-09"}' | jq -r .runId)
curl -s -H "X-API-Key: $KEY" \
  "localhost:8080/api/v1/retail/replenishment-runs/$RUN_ID/proposals" | jq
curl -s -X POST -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  'localhost:8080/api/v1/retail/purchase-orders' \
  -d "{\"runId\":\"$RUN_ID\",\"storeId\":\"ST-001\",\"supplierId\":\"SUP-001\"}" | jq
```

`asOfDate` is optional and defaults to the latest inventory snapshot. See
[docs/retail](docs/retail/README.md) for the data model, KPI definitions, and assumptions.

Retail list endpoints use two pagination shapes. Offset-token endpoints (stores, movements,
dead letters, replenishment runs/proposals/history, purchase orders/lines/history, supplier
transmissions) return `{items, nextPageToken, pageSize}`; follow `nextPageToken` until it is absent:

```bash
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/movements?storeId=ST-001&limit=50' | jq '.items | length, .nextPageToken'
```

`/retail/inventory` and `/retail/replenishment` are ranked top-N reads: they return
`{items, asOfDate, pageSize, truncated}` and have no continuation token; when `truncated=true`, add
filters or raise `pageSize`. `/retail/inventory/reconciliation` is also a bounded report: it returns
`truncated` and has no continuation token.

Offset tokens are opaque (base64url offsets), bounded to 512 characters and a maximum offset, and
rejected with `400 INVALID_REQUEST` when malformed. `RETAIL_DEFAULT_PAGE_SIZE` is the default for
offset-paginated lists; PO lines are the exception with a literal default of 200 (cap 1,000).
`RETAIL_MAX_PAGE_SIZE` (default 200) caps the retail read endpoints; other lists have fixed caps:
dead letters, runs, purchase orders/history, and supplier transmissions 200, PO lines 1,000,
proposals 5,000.
`/stores` names the parameter `pageSize` for historical reasons.

An unknown store is `404 RESOURCE_NOT_FOUND` for inventory, replenishment, dashboard,
reconciliation, and run creation. For list reads, an existing store with no matching rows is an empty
page, or `BALANCED` for reconciliation; it is not an error.

A retail read whose owned dataset or table is missing returns `503 DATASET_NOT_PROVISIONED` (not
retryable; run the schema/seed job). When no snapshot or ledger data exists at all, the read returns
`503 DATA_NOT_READY` (retry after the next load).

Publish an event asynchronously:

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST localhost:8080/api/v1/retail/events \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{
    \"eventId\": \"evt-20261109-0001\",
    \"eventType\": \"SALE\",
    \"occurredAt\": \"$NOW\",
    \"storeId\": \"ST-001\",
    \"productId\": \"SKU-0001\",
    \"quantity\": 2,
    \"payload\": {\"channel\": \"IN_STORE\", \"sourceDocumentId\": \"POS-1\"}
  }"
```

`quantity` is positive for `SALE`, `RETURN`, `SHIPMENT`, and `OPENING_BALANCE`;
`INVENTORY_ADJUSTMENT` uses a signed value. The local default is a bounded in-memory broker with a local relay; set
`MESSAGING_PUBSUB_ENABLED=true` for Pub/Sub. The consumer writes a signed movement with a `MERGE` on
`(event_id, business_date)`; retries, DLQ, and idempotency rules are in
[docs/messaging](docs/messaging/README.md).

### Showcase and review

- **Demo console:** `/demo/index.html` — a read-only UI with a store picker, store KPIs, tabbed
  inventory/replenishment/decision views, searchable and sortable tables, risk filters, and a run
  drill-down. Paste the read-only key; it is stored only in session storage. See
  [docs/demo-console.md](docs/demo-console.md).
- Full API reference: Swagger UI at `/swagger-ui/index.html` when `SPRINGDOC_ENABLED=true`; use
  **Authorize** with `X-API-Key`.
- Reviewer guide: [docs/showcase.md](docs/showcase.md) (read-only tour, agent prompt, operator arc).
- Read-only vs operator keys: with `ADMIN_API_KEY` set, `API_KEY` is read-only and the admin key owns
  writes/admin. Hand a reviewer only the read key.

### Endpoints

| Method | Path | Auth | Description |
| --- | --- | --- | --- |
| `POST` | `/api/v1/query` | `ROLE_API` or `SCOPE_bigquery.query` | Run or dry-run one SQL statement |
| `GET` | `/api/v1/jobs/{jobId}/results` | `ROLE_API` or `SCOPE_bigquery.query` | Page through results of an existing job |
| `GET` | `/api/v1/datasets` | `ROLE_API` / `SCOPE_bigquery.read` / `SCOPE_bigquery.query` | Paginated dataset list |
| `GET` | `/api/v1/datasets/{datasetId}/tables` | same | Paginated table list |
| `GET` | `/api/v1/datasets/{datasetId}/tables/{tableId}/schema` | same | Column list, including nested fields |
| `GET` | `/api/v1/retail/stores` | `ROLE_API` / `SCOPE_retail.read` | Active stores |
| `GET` | `/api/v1/retail/inventory` | same | Inventory positions, demand cover, stockout risk |
| `GET` | `/api/v1/retail/replenishment` | same | Replenishment recommendations |
| `GET` | `/api/v1/retail/dashboard` | same | Store inventory/sales/shipment/promotion KPIs |
| `GET` | `/api/v1/retail/inventory/movements` | `ROLE_API` / `SCOPE_retail.read` | Inventory movement ledger |
| `GET` | `/api/v1/retail/inventory/reconciliation` | `ROLE_API` / `SCOPE_retail.admin` | Ledger-to-snapshot drift report |
| `POST` | `/api/v1/retail/inventory/projection` | `ROLE_API` / `SCOPE_retail.admin` | Rebuild/refresh the on-hand projection |
| `POST` | `/api/v1/retail/events` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Accept a retail event for async processing |
| `GET` | `/api/v1/retail/events/dead-letters` | `ROLE_API` / `SCOPE_retail.admin` | List durable dead letters |
| `POST` | `/api/v1/retail/events/dead-letters/{eventId}/replay` | `ROLE_API` / `SCOPE_retail.admin` | Republish one dead letter |
| `POST` | `/api/v1/retail/replenishment-runs` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Create/replace a replenishment run |
| `GET` | `/api/v1/retail/replenishment-runs` | `ROLE_API` / `SCOPE_retail.read` | List recent runs |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}` | same | Get one run |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}/proposals` | same | Prioritized proposals for a run |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/approve` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Approve a proposal |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/reject` | same | Reject a proposal |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/order` | same | Mark ordered with an external reference |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/history` | `ROLE_API` / `SCOPE_retail.read` | Decision audit trail |
| `POST` | `/api/v1/retail/purchase-orders` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Create a draft PO from approved proposals |
| `GET` | `/api/v1/retail/purchase-orders` | `ROLE_API` / `SCOPE_retail.read` | List purchase orders |
| `GET` | `/api/v1/retail/purchase-orders/{poId}` | same | Get one PO |
| `GET` | `/api/v1/retail/purchase-orders/{poId}/lines` | same | PO lines |
| `POST` | `/api/v1/retail/purchase-orders/{poId}/submit` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Submit a draft PO |
| `POST` | `/api/v1/retail/purchase-orders/{poId}/cancel` | same | Cancel a draft PO |
| `GET` | `/api/v1/retail/purchase-orders/{poId}/history` | `ROLE_API` / `SCOPE_retail.read` | PO lifecycle audit |
| `POST` | `/api/v1/retail/purchase-orders/{poId}/transmit` | `ROLE_API` / `SCOPE_retail.write` / `SCOPE_retail.admin` | Enqueue a submitted PO for supplier transmission |
| `GET` | `/api/v1/retail/supplier-transmissions` | `ROLE_API` / `SCOPE_retail.read` | List outbox transmissions |
| `GET` | `/api/v1/retail/supplier-transmissions/{transmissionId}` | same | Get one transmission |
| `POST` | `/api/v1/retail/supplier-transmissions/{transmissionId}/retry` | `ROLE_API` / `SCOPE_retail.admin` | Retry a FAILED transmission |
| `POST` | `/api/v1/retail/supplier-transmissions/{transmissionId}/ack` | `ROLE_API` / `SCOPE_retail.admin` | Mark a SENT transmission acknowledged |
| `GET` | `/actuator/health/liveness` | public | JVM liveness (used by Docker) |
| `GET` | `/actuator/health/readiness` | public | Readiness (includes Postgres and BigQuery) |
| `GET` | `/actuator/health` | `ROLE_API` / `SCOPE_retail.admin` | Aggregate health |
| `GET` | `/actuator/prometheus` | `ROLE_API` / `SCOPE_retail.admin` | Prometheus scrape endpoint |
| `GET` | `/v3/api-docs` | public while enabled | OpenAPI JSON |
| `GET` | `/openapi/springtime-v1.json` | `ROLE_API` / `SCOPE_retail.admin` | Pinned OpenAPI v1 contract |
| `GET` | `/swagger-ui/index.html` | public while enabled | Swagger UI |

### Contract and versioning

- Contract: authenticated `GET /openapi/springtime-v1.json` (also committed at
  `src/main/resources/static/openapi/springtime-v1.json`).
- Live spec: `GET /v3/api-docs`.
- Response version: `X-API-Version: 1`. The path (`/api/v1`) is authoritative.
- Regenerate after an API change: `make openapi`; CI fails on drift (`make openapi-check`).
- Breaking changes require `/api/v2` and `springtime-v2.json`; additive changes are allowed in v1.
  See [ADR 0003](docs/adr/0003-api-contract-versioning-error-protocol.md).

### Error example

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

Switch on `code`; see [docs/api/errors.md](docs/api/errors.md) for the full catalog and retry rules.
`POST /api/v1/query` is non-idempotent and billable, so upstream 5xx/429/504 are not automatically
retryable until an idempotency key exists. Version/idempotency/state conflicts and unique-constraint
violations are `409 CONFLICT`; policy refusals are `409 BUSINESS_RULE_VIOLATION`; a missing retail
dataset is `503 DATASET_NOT_PROVISIONED` and must not be retried.

## Configuration

Application settings are bound from `application.yml` and overridable with environment variables.
Compose-only variables (`API_PORT`, `GCLOUD_CONFIG_DIR`) and Logback's `LOG_ASYNC_QUEUE_SIZE` are
separate.

| Variable | Default | Meaning |
| --- | --- | --- |
| `GOOGLE_CLOUD_PROJECT` | — | GCP project id. **Required** (startup fails when unset). |
| `BIGQUERY_LOCATION` | `US` | Job location. Must match your datasets (`US`, `EU`, `europe-west3`, ...). |
| `BIGQUERY_DEFAULT_DATASET` | — | Default dataset for unqualified table names. |
| `BIGQUERY_MAX_BYTES_BILLED` | `1073741824` (1 GiB) | Per-query byte ceiling. BigQuery rejects queries above it. |
| `BIGQUERY_MAX_RESULTS` | `1000` | Server ceiling for rows returned per call. |
| `BIGQUERY_USE_QUERY_CACHE` | `true` | Server caching policy. Callers may enable caching but cannot disable it when this is true. |
| `BIGQUERY_READ_ONLY` | `true` | Restrict `/api/v1/query` to read-only statements. |
| `BIGQUERY_MAX_CONCURRENT_QUERIES` | `8` | In-flight BigQuery job ceiling; excess requests get `429`. |
| `BIGQUERY_ACQUIRE_TIMEOUT` | `2s` | How long a request waits for a query slot. |
| `BIGQUERY_QUERY_TIMEOUT` | `60s` | Server-side BigQuery job timeout. |
| `BIGQUERY_WRITER_MAX_BYTES_BILLED` | `1073741824` | Per-statement billed-byte ceiling for controlled DML. |
| `BIGQUERY_WRITER_MAX_CONCURRENT_QUERIES` | `4` | Concurrent controlled-DML ceiling per instance. |
| `BIGQUERY_WRITER_ACQUIRE_TIMEOUT` | `2s` | Wait for a writer slot before returning 429. |
| `MAX_REQUEST_BYTES` | `262144` | Request body ceiling; larger requests get `413`. |
| `MAX_SQL_LENGTH` | `100000` | Maximum SQL text length. |
| `MAX_PARAMETER_VALUE_LENGTH` | `65536` | Maximum characters per parameter value. |
| `MAX_PARAMETERS` | `100` | Maximum number of query parameters. |
| `MAX_RESPONSE_BYTES` | `5000000` | Approximate row budget; responses set `truncated=true` and omit `nextPageToken`. |
| `LOG_ASYNC_QUEUE_SIZE` | `1024` | Bounded JSON log queue; a full queue applies backpressure instead of dropping lines. |
| `TRACING_ENABLED` | `true` | Enable Micrometer/OpenTelemetry tracing and OTLP export; `false` keeps request ids only. |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Head sampling probability for new traces. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP/HTTP traces endpoint; export never blocks startup. |
| `ACCESS_LOG_ENABLED` | `true` | One structured access line per request. |
| `PAGE_DEFAULT_SIZE` | `100` | Default page size for catalog/job results. |
| `PAGE_MAX_SIZE` | `1000` | Hard page-size ceiling. |
| `RETAIL_DATASET` | `retail_demo` | BigQuery dataset owned by the retail domain. |
| `RETAIL_DEFAULT_PAGE_SIZE` | `50` | Default retail page size. |
| `RETAIL_MAX_PAGE_SIZE` | `200` | Hard retail page-size ceiling. |
| `RETAIL_DEMAND_WINDOW_DAYS` | `28` | Trailing demand window for cover/risk. |
| `RETAIL_TARGET_COVER_DAYS` | `14` | Target cover used for order quantity. |
| `RETAIL_MAX_BYTES_BILLED` | `268435456` (256 MiB) | Per-query retail ceiling. |
| `RETAIL_CURRENCY` | `USD` | Default currency for movements without a payload currency. |
| `RETAIL_EVENT_MAX_FUTURE_SKEW` | `5m` | Allowed clock skew for `occurredAt`. |
| `RETAIL_EVENT_MAX_AGE` | `365d` | Oldest event age accepted at intake; dead-letter replays may be older. |
| `RETAIL_RECONCILIATION_TOLERANCE` | `0` | Default allowed absolute ledger/snapshot drift. |
| `RETAIL_BUSINESS_ZONE` | `UTC` | Zone for "today" checks on business dates (e.g. `America/New_York`). |
| `RETAIL_LEDGER_BATCH_ENABLED` | `true` | Coalesce concurrent ledger writes into one BigQuery MERGE. |
| `RETAIL_LEDGER_BATCH_SIZE` | `100` | Maximum movements per batch. |
| `RETAIL_LEDGER_BATCH_LINGER_MS` | `25` | Time to wait for more rows before flushing. |
| `RETAIL_LEDGER_BATCH_MAX_PENDING` | `2000` | Bounded queue before backpressure/fail-closed. |
| `RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES` | `33554432` (32 MiB) | Retained-payload byte budget; rejects with `LEDGER_OVERLOAD` before the heap is at risk. |
| `RETAIL_LEDGER_BATCH_WAIT_TIMEOUT` | `75s` | Max caller wait for its batch to commit. |
| `RETAIL_PROJECTION_ENABLED` | `false` | Run the scheduled on-hand projection refresh. |
| `RETAIL_PROJECTION_INTERVAL_MS` | `900000` | Scheduler delay between projection refreshes. |
| `RETAIL_PROJECTION_LOOKBACK_DAYS` | `7` | Days recomputed for late-arriving events. |
| `FLYWAY_ENABLED` | `false` (Compose and `.env.example`: `true`) | Run Flyway migrations on startup; production runs them as a separate deploy job. |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/springtime` | Postgres JDBC URL. |
| `DB_MAX_POOL_SIZE` | `10` | Hikari pool size. |
| `DB_CONNECTION_TIMEOUT_MS` | `5000` | Hikari connection acquisition timeout. |
| `DB_VALIDATION_TIMEOUT_MS` | `2000` | Hikari validation timeout. |
| `DB_IDLE_TIMEOUT_MS` | `600000` | Hikari idle timeout. |
| `DB_MAX_LIFETIME_MS` | `1800000` | Hikari maximum connection lifetime. |
| `DB_SOCKET_TIMEOUT_SECONDS` | `30` | PostgreSQL socket read timeout. |
| `DB_CONNECT_TIMEOUT_SECONDS` | `5` | PostgreSQL connect timeout. |
| `DATABASE_USERNAME` | `springtime` | Postgres user. |
| `DATABASE_PASSWORD` | `springtime` | Postgres password. |
| `SUPPLIER_GATEWAY` | unset | Supplier adapter selector. `local` is a demo only; unset fails startup because a fake supplier must not be the default. |
| `SUPPLIER_OUTBOX_ENABLED` | `true` | Enable the durable supplier outbox. |
| `SUPPLIER_OUTBOX_WORKER_ENABLED` | `true` | Run the outbox worker. |
| `SUPPLIER_OUTBOX_POLL_INTERVAL_MS` | `5000` | Worker poll interval. |
| `SUPPLIER_OUTBOX_BATCH_SIZE` | `20` | Claimed rows per tick. |
| `SUPPLIER_OUTBOX_MAX_ATTEMPTS` | `8` | Attempts before FAILED. |
| `SUPPLIER_OUTBOX_INITIAL_DELAY` | `1s` | Initial retry delay. |
| `SUPPLIER_OUTBOX_MULTIPLIER` | `2.0` | Retry multiplier. |
| `SUPPLIER_OUTBOX_MAX_DELAY` | `5m` | Retry ceiling. |
| `SUPPLIER_OUTBOX_JITTER` | `0.2` | Jitter fraction. |
| `MESSAGING_PUBSUB_ENABLED` | `false` | Use Pub/Sub instead of the bounded in-memory broker. Required in the `prod`/`production` profile. |
| `MESSAGING_EVENTS_TOPIC` | `retail-events` | Event topic name. |
| `MESSAGING_DLQ_TOPIC` | `retail-events-dlq` | Dead-letter topic name; must differ from the event topic. |
| `MESSAGING_SUBSCRIPTION` | `retail-events-worker` | Pub/Sub subscription used by the consumer. |
| `MESSAGING_CONSUMER_ENABLED` | `true` | Start the Pub/Sub consumer when Pub/Sub is enabled. |
| `MESSAGING_LOCAL_CONSUMER_ENABLED` | `true` | Start the local in-memory relay. |
| `MESSAGING_LOCAL_CONSUMER_POLL_INTERVAL_MS` | `500` | Local relay poll interval. |
| `MESSAGING_RETRY_MAX_ATTEMPTS` | `5` | In-process attempts before a retryable failure is nacked for broker redelivery; non-retryable failures dead-letter immediately. |
| `MESSAGING_MAX_IN_MEMORY_MESSAGES` | `1000` | Capacity of each local in-memory broker queue (events and dead letters). |
| `MESSAGING_RETRY_INITIAL_DELAY` | `200ms` | Initial backoff. |
| `MESSAGING_RETRY_MULTIPLIER` | `2.0` | Exponential multiplier. |
| `MESSAGING_RETRY_MAX_DELAY` | `10s` | Backoff ceiling. |
| `MESSAGING_RETRY_JITTER` | `0.2` | Jitter fraction (`0.2` = ±20%). |
| `SPRINGDOC_ENABLED` | `false` (Compose/`.env.example` sets `true`) | Enable `/v3/api-docs` and Swagger UI. Disable for shared environments. |
| `API_KEY` | generated by `make env` | Shared secret for local use; required unless OIDC is configured. |
| `API_KEY_PREVIOUS` | empty | Previous key kept valid during rotation (`make key` moves it here). |
| `ADMIN_API_KEY` | empty | Optional separate operator/admin key; when set, `API_KEY` carries read-only scopes. |
| `OIDC_ROLES_CLAIM` | `roles` | Custom claim mapped to `SCOPE_<role>` authorities. |
| `OIDC_ALLOWED_DOMAINS` | empty | Google Workspace `hd` domains granted read-only `SCOPE_retail.read`. |
| `OIDC_ALLOWED_SUBJECTS` | empty | Service-account `sub` values granted read-only `SCOPE_retail.read`. |
| `ALLOW_INSECURE_OIDC` | `false` | Permit `http://` issuers for local OIDC only. |
| `ALLOW_API_KEY_IN_PRODUCTION` | `false` | Allow the operator API key when the `prod` profile is active. Prefer OIDC. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | OIDC issuer URL; enables JWT bearer auth. |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | — | Required JWT audience. |
| `API_PORT` | `8080` | Host port (loopback by default). |
| `GCLOUD_CONFIG_DIR` | `${HOME}/.config/gcloud` | Host ADC directory mounted into the container. |

`maxResults` and `maximumBytesBilled` can be sent per request but are always **clamped down** to the
server ceilings — a client cannot raise them.

## Data access and schemas

BigQuery is the database. There is **no JPA/ORM layer** because BigQuery has no entity identity,
transactions, relationships, or startup schema; a fake `save()`/`findAll()` abstraction would hide
scan cost and byte billing.

- `BigQueryGateway` is the application port; `GoogleBigQueryGateway` owns SDK calls, pagination
  options, and schema field selection. Controlled writes go through the narrow `BigQueryDmlGateway`
  only.
- **One retail read path.** Every retail adapter calls `RetailSqlExecutor`, and only the
  `retail.query` package constructs a `QueryRequest`. The executor loads owned SQL, applies the
  retail byte budget, labels jobs `surface=retail`, and maps a missing dataset/table to
  `503 DATASET_NOT_PROVISIONED`. Retail reads use their own concurrency slots (half
  `BIGQUERY_MAX_CONCURRENT_QUERIES`, minimum 1) so operator queries cannot starve store reads.
- Retail SQL lives in `src/main/resources/sql/retail`; shared fragments live in
  `sql/retail/partials` and are expanded with `{{include:name}}` by `RetailSqlCatalog`. Includes may
  nest up to `MAX_INCLUDE_DEPTH = 2`; cycles and unresolved names fail fast.
- Table schemas come from the live BigQuery table definition (`Table.getDefinition().getSchema()`),
  not a checked-in duplicate. Nested `STRUCT`/`REPEATED` fields are returned as `subFields`.
- `schemaHash` (`sha256:...`) is a deterministic hash of the column tree; use it as the practical
  schema version and cache validator.
- Query results are dynamic by nature; rows are schema-driven typed maps, not generated entities.
- **Migrations:** BigQuery DDL is owned under `db/retail/schema` and applied by
  `make retail-schema`; Postgres uses Flyway under `src/main/resources/db/migration`. The forward-only
  policy (versioned SQL, ledger, expand/contract, snapshots, separate deploy job) is recorded in
  [ADR 0005](docs/adr/0005-bigquery-schema-migrations.md) and `db/README.md`. Production does not
  run migrations on app startup; local Compose may.
- **Views/joins:** API-facing views belong in repeatable migrations; analytical transforms move to
  Dataform or dbt later, not the Java request path.
- The Postgres control plane is implemented for purchase orders, lifecycle events, and the supplier
  outbox. See [ADR 0015](docs/adr/0015-postgres-control-plane-and-supplier-outbox.md).

## Events and long-running work

The generic `/api/v1/query` and catalog path is synchronous. Retail events and supplier
transmission are asynchronous:

- retail events publish to **Pub/Sub** or the bounded local broker and return `202`; the request path
  does not write to BigQuery;
- the consumer acknowledges only after the BigQuery ledger write commits (or a dead-letter publish
  succeeds), and dedupes by `event_id` (the MERGE key is `(event_id, business_date)`);
- concurrent movements coalesce into one array-parameter MERGE. The outcome is batch-level: if any
  row inserts, every caller in that batch sees `INSERTED`; a retryable failure fails the whole batch
  and nacks, while deterministic row failures use a bounded per-row fallback;
- permanent failures go to the durable dead-letter store/topic; replay is operator-triggered;
- supplier transmission uses the Postgres outbox worker described in ADR 0015;
- use **Cloud Run Jobs** for long backfills/replays (task timeout up to 168 hours), Workflows for
  multi-step flows, and Dataflow only for continuous high-volume streams.

BigQuery is an OLAP warehouse, not Elasticsearch/OLTP. See
[docs/architecture/async-events.md](docs/architecture/async-events.md) and
[ADR 0007](docs/adr/0007-async-events-and-long-running-work.md).

## Security and cost controls

### Authentication

- **Local:** `X-API-Key`, compared in constant time. `make env` generates a 256-bit key; `make key`
  rotates it. No Basic auth, no sessions, no CSRF tokens.
- **Production:** set
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES`; tokens need `bigquery.query` or
  `bigquery.read` scopes, or a mapped Google role/domain/subject. The two auth mechanisms can run
  together, and `API_KEY_PREVIOUS` enables key rotation. Startup validates the combination.
- Google-issued ID tokens carry no OAuth scopes. Map a GCIP/Firebase `roles` custom claim
  (`{"roles":["bigquery.query"]}`) for operator/query access. A Workspace `hd` domain via
  `OIDC_ALLOWED_DOMAINS` or a service-account `sub` via `OIDC_ALLOWED_SUBJECTS` grants only
  `SCOPE_retail.read`; it does not grant `ROLE_API` or operator endpoints.
- Full Google setup: [docs/auth/README.md](docs/auth/README.md) and
  [ADR 0006](docs/adr/0006-google-authentication.md).
- `API_KEY` is a shared secret, not per-user identity. Set `ADMIN_API_KEY` to split read and operator
  authority. For shared deployments use OIDC, terminate TLS at a gateway, add rate limiting, and
  consider per-tenant identity + dataset allowlists.

### Traffic identity and telemetry

- Send `X-Request-Id` to propagate a client correlation id; otherwise a UUID is generated. It is
  echoed in the response, included in ProblemDetail `requestId`, written to logs as `requestId`, and
  added to BigQuery job labels (`request_id=r-...`). `JobLabels` is the single label builder and
  normalises the value to lowercase `[a-z0-9_-]` (max 63 chars) on every surface.
- Logs are JSON on stdout: `timestamp`, `level`, `logger`, `thread`, `message`, `service`, and the
  allow-listed MDC fields `requestId`, `clientId`, `jobId`, `traceId`, `spanId`. Access logs add
  method, path, status, latency, and remote address; application logs add the active BigQuery
  `job` id.
- Tracing is Micrometer/OpenTelemetry with W3C context propagation. Incoming HTTP spans parent the
  request; published events carry `traceparent` in broker attributes; consumers start a child
  `retail-event.consume` span and fail back to a no-op when tracing is disabled. Spans export over
  OTLP/HTTP (default `http://localhost:4318/v1/traces`). Start a local collector with
  `docker run --rm -p 4318:4318 otel/opentelemetry-collector:latest`; point at a real collector in
  production. The local demo has no collector, so export simply retries in the background.
- Query and DML job labels include `trace_id` and `span_id` where a span is active, so a slow job in
  the BigQuery audit log maps back to the request and broker message that created it.
- Prometheus scrapes `/actuator/prometheus` (authenticated); build info is at `/actuator/info`.
  Event processed/duplicate, retry, sink failure, DLQ, movement read, reconciliation drift, and
  ledger batch counters are emitted; the projection freshness gauge is
  `retail.inventory.projection.last.success.timestamp.seconds`. Custom query byte/latency metrics
  are still a follow-up.

### Defensive limits

- Request body (`MAX_REQUEST_BYTES`), SQL length, parameter count (100), per-parameter length, rows,
  query bytes, concurrency, and job timeout are all bounded and configurable.
- `maximumBytesBilled` is enforced per job. It does not cap the number of jobs; the concurrency
  semaphore and BigQuery quotas/budgets do.
- `maxResults` limits row count; `MAX_RESPONSE_BYTES` caps the serialized row budget. When the budget
  is hit, `truncated=true` and `nextPageToken` is null; a single row larger than the budget is dropped
  rather than returned whole. Narrow the query or lower `maxResults`.
- The default Compose stack binds `127.0.0.1`, drops capabilities, runs read-only, and mounts ADC
  read-only.

### Boundary

- **IAM is the real data boundary.** The keyword guard is a convenience filter, not a SQL parser.
  Run the public API with a read-only identity; do not rely on `BIGQUERY_READ_ONLY`.
- **Writes are a separate path.** `BigQueryDmlGateway` is the only DML entry point; the ledger
  sink, dead-letter store/replay, replenishment run/proposal writes, and projection refresh use it.
  In production, run the consumer and admin/write endpoints with a dataset-scoped `dataEditor`
  identity, and set `MESSAGING_CONSUMER_ENABLED=false` on the public read API. A single demo
  deployment may use one identity, but do not expose it to untrusted callers. The generic
  `/api/v1/query` path is read-only.
- Actuator details require `ROLE_API` or `SCOPE_retail.admin`; keep it on loopback or a private
  management port before exposure.
- Dry runs skip the read-only check because they execute nothing.
- Do not commit `.env`, `secrets/`, ADC files, or service-account keys; all are ignored by Git and
  Docker.

### Using a service-account key instead of ADC

Create `compose.override.yaml`:

```yaml
services:
  api:
    environment:
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/key.json
    volumes:
      - ./secrets/key.json:/secrets/key.json:ro
```

`secrets/`, `*.pem`, and `*key.json` are ignored by Git and Docker.

## Tests

```bash
make test            # unit, MVC, security, contract, architecture, messaging, JDBC, and filter tests; no GCP calls
make verify          # full Maven verification
make it              # opt-in real-BigQuery integration tests
make retail-schema   # apply the forward-only retail ledger schema (bq + ADC required)
make retail-seed     # load the deterministic retail demo dataset (bq + ADC required)
make openapi         # regenerate the committed OpenAPI contract
make obs-up          # API + Prometheus + Tempo + Loki + Grafana + Alloy + Alertmanager
make loadtest        # k6 smoke (SCRIPT=/scripts/retail-read.js for another scenario)
make chaos           # local failure injection: Postgres, broker, BigQuery mapping
make demo-seed       # seed one run/approval/PO/outbox arc (BASE_URL, ADMIN_API_KEY)
make obs-config      # validate alerts, dashboard, alert routing, alloy, compose, scripts
```

Operations documentation (SLOs, failure matrix, alerts, runbooks) is at
[docs/operations](docs/operations/README.md); load and failure scenarios live under
[ops/](ops). The observability stack exports the same metrics the alerts use, and CI validates the
Prometheus rules, Alertmanager/Alloy config, dashboard JSON, Compose overlay, and k6 scripts. The
hosted-demo path (Cloud Run + Cloud SQL + BigQuery, ~$10–15/month) is documented in
[docs/deployment/gcp.md](docs/deployment/gcp.md) with a deploy script under `deploy/gcp/`.

The suite covers the typed codec, parameter binder, SQL guard, page-token encoding/decoding,
pagination envelopes, security chain (401/403/public probes), request size limits,
request-id/version propagation, startup validation rules, schema hashing, retail
query/mapping/controller behavior, SQL include rendering/drift, event translation, idempotent
ledger writes, retry/DLQ/replay, trace-context injection/extraction, projection refresh,
replenishment run idempotency, reconciliation, module boundaries, and the committed OpenAPI
contract. Postgres repositories are
tested against a real PostgreSQL Testcontainer; full-context tests use a test-only profile with
Flyway disabled. The real-BigQuery integration test is opt-in:

```bash
RUN_BIGQUERY_IT=true GOOGLE_CLOUD_PROJECT=your-project-id ./mvnw test -Dtest=BigQueryIntegrationTest
```

It issues a free dry run and a `SELECT 1, 1.5, TRUE`, asserting the typed JSON contract. It does not
yet load the retail seed or exercise the ledger against live BigQuery; run `make retail-schema` and
`make retail-seed` in a disposable project for that.

## CI and version policy

- `.github/workflows/ci.yml` runs `./mvnw verify` (including the OpenAPI drift test and ArchUnit
  rules), starts PostgreSQL, builds the container, runs Flyway, and smoke-tests liveness, the
  authenticated purchase-order read, OpenAPI, unauthenticated 401, and
  authenticated Prometheus on every push/PR.
- Dependabot checks Maven, Docker, and GitHub Actions weekly.
- `make versions` shows available dependency and plugin updates.
- `make openapi` regenerates the committed contract; `make openapi-check` fails on drift.
- Platform decisions live in [ADR 0001](docs/adr/0001-platform-baseline.md),
  [ADR 0002](docs/adr/0002-auth-traffic-pagination-limits.md),
  [ADR 0003](docs/adr/0003-api-contract-versioning-error-protocol.md), and
  [ADR 0004](docs/adr/0004-bigquery-gateway-and-schema-source.md).

## Production notes

- Deploy the image to Cloud Run, GKE, or ECS. Use **Workload Identity** / task roles instead of
  mounting ADC.
- Prefer OIDC bearer tokens over `API_KEY`; set a required audience and the `bigquery.query` /
  `bigquery.read` scopes. Keep `BIGQUERY_READ_ONLY=true` and a tight byte ceiling.
- Split the public API and the ledger consumer into separate deployments/identities: API with a
  read-only BigQuery SA and `MESSAGING_CONSUMER_ENABLED=false`; worker with a dataset-scoped
  `dataEditor` SA and `MESSAGING_CONSUMER_ENABLED=true`.
- Run Postgres as a managed OLTP instance and Flyway as a separate deploy job, not on app startup.
  The supplier outbox worker should run as its own deployment with the writer identity.
- Run the on-hand projection as a separate job/scheduler with the writer identity; keep
  `RETAIL_PROJECTION_ENABLED=false` on the public API. Tune the lookback to the event lateness SLO.
- Keep replenishment runs single-store and bounded (5,000 proposals). Move multi-store/backfill runs
  to a Cloud Run Job or Workflow before scaling them.
- Set `SPRINGDOC_ENABLED=false`, keep actuator on loopback or a private management port, and put TLS,
  WAF, and global rate limiting at the gateway.
- Point readiness at `/actuator/health/readiness`; keep container health on
  `/actuator/health/liveness`. Readiness includes BigQuery, so bad credentials stop traffic.
- Set up BigQuery custom quotas, budget alerts, audit logs, and per-environment GCP projects.
- Ship logs to your aggregator and point `OTEL_EXPORTER_OTLP_ENDPOINT` at your collector; keep
  sampling bounded (10% default). SLOs, error budgets, the dependency failure matrix, alert
  thresholds, and runbooks live in [docs/operations](docs/operations/README.md); the Prometheus
  rules are committed at [ops/prometheus/springtime-alerts.yml](ops/prometheus/springtime-alerts.yml)
  and a contract test fails if an alert references a metric the code no longer emits. The open-source
  telemetry stack (Prometheus, Tempo, Loki, Grafana, Alloy, Alertmanager) is in
  `compose.observability.yaml`; the hosted deployment and its cost guardrails are in
  [docs/deployment/gcp.md](docs/deployment/gcp.md).
- Project-specific deployment detail — real project IDs, keys, SQL connection strings, logs, and
  command history — belongs in `.local/`, which is gitignored and dockerignored. Committed docs stay
  agnostic. Use [docs/deployment/gcp.md](docs/deployment/gcp.md) as the template.
- Add per-key rate limits and a query-submission idempotency key before exposing this to
  untrusted clients.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Property: bigquery.projectId ... must not be blank` | Set `GOOGLE_CLOUD_PROJECT` in `.env`. |
| `STARTUP_CONFIG_INVALID` | Read the listed environment variables in the startup block; no server starts. |
| `No authentication configured` at startup | Run `make key` (or set `API_KEY`), or configure OIDC issuer/audience. |
| OpenAPI contract drift | Run `make openapi`, review the JSON diff, and commit it. |
| `401 Unauthorized` | Send `X-API-Key: $KEY` or a valid bearer token. Check the response `requestId` in logs. |
| `400 INVALID_REQUEST` on a list call | `pageToken` was not produced by this API (or exceeded the offset ceiling); restart from the first page without a token. |
| No traces in your collector | Check `OTEL_EXPORTER_OTLP_ENDPOINT`, that `TRACING_ENABLED` is not `false`, and that the sample rate is non-zero. |
| `413 Payload too large` | Reduce the JSON body below `MAX_REQUEST_BYTES` (default 256 KiB). |
| `Your default credentials were not found` | Run `gcloud auth application-default login`, or mount a key and set `GOOGLE_APPLICATION_CREDENTIALS`. |
| `role "springtime" does not exist` | Start the Compose Postgres or set `DATABASE_USERNAME`/`DATABASE_PASSWORD`/`DATABASE_URL`. |
| Flyway migration failure | Check `FLYWAY_ENABLED`, `DATABASE_URL`, and that Postgres is reachable; run migrations as a separate job in production. |
| Container cannot see credentials | Set `GCLOUD_CONFIG_DIR` in `.env` to the host directory containing `application_default_credentials.json`. |
| `/actuator/health/readiness` is DOWN | Readiness includes Postgres and the BigQuery dry-run probe: check `DATABASE_URL`, project id, ADC, IAM, and `BIGQUERY_LOCATION`. |
| `/api/v1/retail/**` returns `503 DATASET_NOT_PROVISIONED` | The owned dataset/table is missing: run `make retail-schema`, then `make retail-seed`. Do not retry until it exists. |
| `/api/v1/retail/**` returns `503 DATA_NOT_READY` | No snapshot or ledger data exists yet: seed or wait for the next load, then retry. An existing store with no rows returns an empty page, not this error. |
| `/api/v1/retail/**` returns `404 RESOURCE_NOT_FOUND` | The store does not exist. Check `storeId` against `GET /api/v1/retail/stores`; an existing store with no rows returns an empty page (or `BALANCED` for reconciliation). |
| `Access Denied` / 502 | Grant `roles/bigquery.jobUser` on the project and viewer on the dataset; check the service account. |
| `Only SELECT, WITH, ...` | Send a read-only statement or set `BIGQUERY_READ_ONLY=false`. |
| `Unknown property` / 400 | Remove the misspelled field. Unknown JSON fields are rejected on purpose (`dryrun` vs `dryRun`). |
| `429 Too Many Requests` | The concurrency cap is full; retry after `Retry-After` or raise `BIGQUERY_MAX_CONCURRENT_QUERIES` deliberately. |
| `Query exceeded limit for bytes billed` | Raise `BIGQUERY_MAX_BYTES_BILLED` or narrow the query. |
| `Not found: Dataset ... in location ...` | Make `BIGQUERY_LOCATION` match the dataset location. |
