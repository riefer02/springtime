# Architecture flow

This is a modular monolith. One Spring Boot process, one Maven module, package-level modules, and
ArchUnit checks for the boundaries.

## Request path

```
Client
  -> request-id / size / version filters
  -> Spring Security (API key or OIDC, stateless)
  -> controller
  -> application service
  -> port
  -> adapter (BigQuery or Postgres)
```

- API packages contain controllers; wire records live in neutral `model.query`, `retail.*.dto`, and
  `control.supplier.dto` packages.
- Application services own policy: limits, idempotency, state transitions, and error mapping.
- Ports describe what the application needs. Adapters own BigQuery SQL, Pub/Sub, and HTTP details.
- Controllers never call the BigQuery SDK.

## Generic query path

`POST /api/v1/query` goes through `BigQueryService`: SQL length/parameter checks, read-only guard,
byte/row/timeout limits, concurrency semaphore, then `BigQueryGateway`. Catalog and job-result reads
use the same gateway. This path is read-only. Separate admin/worker paths (ledger sink, DLQ
persistence/replay, replenishment writes, projection refresh) use `BigQueryDmlGateway` and need a
writer identity.

## Retail read path

`RetailController` and `InventoryController` call `RetailService` or `InventoryService`.
`ReplenishmentRunController` calls `ReplenishmentRunService`/`ProposalDecisionService`, and
`PurchaseOrderController`/`SupplierTransmissionController` call the purchase and outbox services.
Read adapters use `RetailSqlExecutor`, which loads fixed SQL, substitutes the configured
project/dataset, and executes it through `BigQueryService`. Only the `retail.query` package may
construct a `QueryRequest`; retail jobs are labeled `surface=retail` and use their own concurrency
slots (half `BIGQUERY_MAX_CONCURRENT_QUERIES`, minimum 1), so operator `/query` traffic cannot starve
store reads. A missing owned dataset/table maps to `503 DATASET_NOT_PROVISIONED` (do not retry), no
snapshot/ledger data maps to `503 DATA_NOT_READY` (retry), and an unknown store maps to
`404 RESOURCE_NOT_FOUND` (an existing store with no rows is an empty page or `BALANCED`). There is no
request-supplied SQL, dataset, or table name. Purchase-order and outbox state lives in Postgres, but
PO creation snapshots approved proposals from BigQuery (the one cross-store read).

## Event path

```
POST /retail/events
  -> translator validates and maps
  -> MessagePublisher (in-memory or Pub/Sub)
  -> consumer (local relay or Pub/Sub subscriber)
  -> RetailEventProcessor
  -> RetailEventTranslator
  -> InventoryMovementSink
  -> MERGE inventory_movements ON (event_id, business_date)
  -> ack only after commit
```

Permanent failures go to a structured dead-letter envelope. The dead-letter store is in-memory
locally and BigQuery in production. `GET /retail/events/dead-letters` lists records; the replay
endpoint republishes the original event. Replay is safe because the ledger dedupes on
`(event_id, business_date)` and reads dedupe by `event_id`. Unexpected
handler bugs are dead-lettered as `UNEXPECTED_FAILURE` instead of being nacked forever. The sink
coalesces concurrent movements into one partition-scoped, array-parameter `MERGE` keyed by
`(event_id, business_date)`; `RETAIL_LEDGER_BATCH_*` bounds the batch, queue, and retained-payload
bytes. A retryable batch failure fails the whole batch and nacks; deterministic row failures use a
bounded per-row fallback. Batch outcomes are batch-level: if any row in the batch inserts, every
caller sees `INSERTED`.

## Inventory and replenishment flow

```
inventory_movements (source of truth)
  -> inventory_movements_deduped
  -> inventory_on_hand_projection (incremental table)
  -> inventory_on_hand_daily (stable view)
  -> retail reads / replenishment runs
```

The projection is refreshed by an admin endpoint or an opt-in scheduler, not synchronously with
events. A refresh holds a Postgres lease, starts from the oldest unprojected movement so late events
are applied, and removes stale rows on a full rebuild. Dedupe uses `event_id` with the tie-break
`(ingested_at, event_time)`, matching the deduped view. Replenishment run creation and proposal
decisions share the same run lease, so a decision cannot race a re-run; the BigQuery writes are still
a multi-job sequence. Runs write
`replenishment_runs` and `replenishment_proposals`. Proposal
decisions use `expectedVersion` optimistic concurrency and append to
`replenishment_proposal_decisions`; `ORDERED` records an external reference, not a generated order.
Approved proposals can become a **draft purchase order** for one run/store/supplier, copied into
`purchase_order_lines` in one JDBC batch with the source proposal version; `purchase_order_events`
records the draft/submitted/cancelled lifecycle. A PO is bounded to 1,000 lines.
Supplier transmission is a **Postgres outbox**: `submit` updates the PO and inserts a durable
outbox row in the same transaction; the manual `POST /purchase-orders/{poId}/transmit` is an
idempotent trigger. The stored payload is a control-owned contract that includes every PO line. A
worker claims rows with `FOR UPDATE SKIP LOCKED`, calls `SupplierGateway`, and records `SENT`/
`ACKED`/`FAILED`. EDI/API adapters sit behind that port. The retail store adapts its PO model to
the control-owned reader port, so the control plane imports no retail package.

## Boundaries

- Retail API packages (including `control.supplier.api`) do not reach query or sink infrastructure.
- Retail domain models do not depend on application or infrastructure packages.
- `control` does not depend on `retail`; retail adapts to control-owned ports.
- `messaging` does not depend on `retail`, `config`, or `control`.
- `web` and `security` do not depend on `retail` or `control`.
- Application and generic `service` packages do not depend on any `api` package.
- Purchase and replenishment application packages do not reach their store packages.
- The event application package uses an inventory sink port, not the BigQuery sink adapter.
- Retail and top-level package slices are cycle-free.

Postgres is the OLTP control plane; BigQuery is the OLAP analytical plane. BigQuery is not a queue,
lock manager, or multi-statement transaction store. The outbox, retries, and state transitions belong
in Postgres; the ledger and analytical read models stay in BigQuery. The remaining non-transactional
edges are documented in the ADRs.
