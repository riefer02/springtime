# Module boundaries

This is a **modular monolith**. One Maven module, clear package boundaries, and executable
architecture tests. The goal is a codebase a senior engineer can navigate without a map taped to the
monitor.

## Modules

| Module | Responsibility | May depend on |
| --- | --- | --- |
| `api` | Generic query/catalog HTTP controllers | `service`, `model`, `model.query`, `messaging`, `web` |
| `service` | Query execution, catalog reads, SQL guard, parameter binding, row codec | `gateway`, `model`, `model.query`, `schema` |
| `gateway` | BigQuery SDK adapters: read port + controlled DML port | SDK, `config` |
| `retail.api` | Retail read controllers | `retail.service`, `retail.model` |
| `retail.service` | Retail application service | `retail.query`, `retail.model`, `config` |
| `retail.query` | Single fixed-SQL read path (`RetailSqlExecutor`, own BigQuery slot pool), `{{include:}}` partial expansion/cache, retail read port | `service`, `model.query`, `retail.model`, `config` |
| `retail.model` | Retail read models | nothing |
| `retail.event.api` | Event intake controller and request/response DTOs | `retail.event.application` |
| `retail.event.dto` | Event/dead-letter wire envelopes | `messaging` |
| `retail.event.model` | Event type and processing exceptions/result | nothing |
| `retail.event.application` | Translation, publication, processing, DLQ, stored dead letters, metrics | `retail.event.dto`, `retail.inventory.application`, `messaging` |
| `retail.event.consumer` | Pub/Sub subscriber and local relay | `retail.event.application`, `messaging` |
| `retail.event.store` | In-memory and BigQuery dead-letter stores | `retail.event.application`, `retail.event.dto`, `retail.query`, `gateway`, `model`, `messaging`, `util` |
| `retail.inventory.model` | Movement, write result, bounds, drift, projection result | nothing |
| `retail.inventory.sink` | Idempotent BigQuery ledger + projection stores, batch buffer | `gateway`, `retail.inventory.application`, `retail.inventory.model`, `retail.query`, `config`, `observability`, `util` |
| `retail.inventory.query` | Ledger/reconciliation/bounds read adapter | `retail.query`, `retail.inventory.model` |
| `retail.inventory.application` | Ledger reads, sink/projection ports, reconciliation, projection orchestration | `retail.inventory.model`, `retail.inventory.query`, `retail.query`, `control.run` |
| `retail.inventory.api` | Ledger, reconciliation, and projection controllers | `retail.inventory.application` |
| `retail.replenishment.model` | Run, proposal, status, summary | nothing |
| `retail.replenishment.application` | Run/decision services, repository/query ports, metrics | `retail.replenishment.model`, `retail.replenishment.dto`, `retail.query`, `config`, `control.run` |
| `retail.replenishment.query` | Replenishment→purchase run adapter | `retail.purchase.application`, `retail.replenishment.application` |
| `retail.replenishment.store` | BigQuery run/proposal repository | `gateway`, `model`, `retail.replenishment.application`, `retail.replenishment.model`, `retail.query`, `util` |
| `retail.replenishment.api` | Run/proposal controllers | `retail.replenishment.application` |
| `retail.purchase.model` | Purchase order, line draft, event, status | nothing |
| `retail.purchase.application` | PO service, approved-proposal/run ports, view mapper, metrics | `retail.purchase.model`, `retail.purchase.dto`, `control` |
| `retail.purchase.store` | Postgres PO repository, approved-proposal/outbox adapters | `retail.purchase.application`, `retail.purchase.model`, `control`, `retail.query` |
| `retail.purchase.api` | PO controllers | `retail.purchase.application`, `retail.purchase.dto` |
| `control.supplier` | Postgres outbox, worker, supplier adapter/reader ports | `control`, `error`, `util`, `model.query`, `observability`, own `dto` |
| `control.run` | Postgres run lease for long-running replenishment work | Spring JDBC only |
| `observability` | Trace context (W3C inject/extract, MDC), BigQuery trace labels | OpenTelemetry/SLF4J only; broker adapters depend on it, not the reverse |
| `util` | Small pure helpers (log sanitization, UUIDs, notes, backoff) | nothing |
| `messaging` | Broker port, payload contract, in-memory/Pub/Sub adapters, retry primitive | Spring/Jakarta, `observability` (trace attributes); no `config`, `control`, or `retail` |
| `security`, `web`, `config` | Platform concerns | no `retail` or `control` dependencies |
| `error`, `health`, `model`, `schema`, `contract` | Errors, health, shared query model, schema hash, API version | platform/`gateway` only |

## Rules enforced by ArchUnit

`src/test/java/com/springtime/bigquery/architecture/ArchitectureTest.java`:

- `messaging` must not depend on `retail`, `config`, or `control`.
- `web` and `security` must not depend on `retail` or `control`.
- Application and generic `service` packages must not depend on any `api` package.
- Top-level package slices must be cycle-free.
- Retail API packages (including `control.supplier.api`) must not reach query or sink infrastructure.
- Retail domain models must not depend on application, infrastructure, or `messaging` packages.
- `control.*` must not depend on `retail.*`; the retail store adapts to control-owned ports.
- `retail.purchase.*` must not depend on `retail.replenishment.*`; purchase owns its approved-proposal
  and run-snapshot ports, and the replenishment adapter implements them.
- The event application package must not depend on the BigQuery inventory sink adapter.
- Only `retail.query` may build a `QueryRequest`; every retail read goes through
  `RetailSqlExecutor`, which owns the byte budget, cache flag, `surface=retail` job labels, its own
  concurrency slots, and the 404/503 mapping (`RESOURCE_NOT_FOUND`, `DATASET_NOT_PROVISIONED`).
- Purchase and replenishment application packages must not reach their store packages.
- The purchase store must not reach the purchase API package.
- `retail.*` slices must be cycle-free.

Wire records live in neutral packages: `model.query` for the generic query surface and
`retail.<module>.dto` / `control.supplier.dto` for module contracts. Application services and the
generic `service` package therefore never depend on a controller package.

Owned retail SQL lives in `src/main/resources/sql/retail`. Shared CTE fragments live in
`sql/retail/partials` and are pulled in with `{{include:name}}`; `RetailSqlCatalog` is the single
loader, cache, and `__PROJECT__`/`__DATASET__` substitutor, so a formula cannot drift between the
planner worklist and the durable proposal query. Includes may nest up to
`MAX_INCLUDE_DEPTH = 2`; cycles, invalid names, and unresolved includes fail fast instead of
reaching BigQuery.

Add a rule when you introduce a new boundary. A failed architecture test is cheaper than a package
graph nobody can reason about.

## Why not Spring Modulith or Maven modules?

- One deployable, one Maven module keeps the build simple and matches the current scope.
- Spring Modulith would add runtime surface for a boundary problem that ArchUnit already solves.
- A future split (worker, ingestion) can reuse the same packages and ports; the event module is
  already shaped for extraction.
