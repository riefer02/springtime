# Retail copilot

A generic retail inventory/replenishment slice over BigQuery. No brand names.

## Pagination

There are two pagination shapes; neither returns a bare array.

**Offset tokens** (stores, movements, dead letters, runs, proposals, decision history, purchase
orders/lines/history, supplier transmissions):

```json
{"items": [...], "nextPageToken": "opaque-token-or-null", "pageSize": 50}
```

- `pageToken` is a base64url offset produced by this service: at most 512 characters, offset at most
  1,000,000. A malformed, oversized, or out-of-range token is `400 INVALID_REQUEST`.
- An unknown store is `404 RESOURCE_NOT_FOUND` for inventory, replenishment, dashboard,
  reconciliation, and run creation; for list reads, an existing store with no matching rows is an
  empty page (or `BALANCED` for reconciliation), never an error or a "healthy" result.
- `nextPageToken` is present only when another row exists: services fetch `limit + 1`, return
  `limit`, and stop issuing tokens at the offset ceiling.
- The token is decoded to an offset and bound as a query parameter. It is never interpolated into
  SQL text.

**Ranked top-N** (`/retail/inventory`, `/retail/replenishment`):

```json
{"items": [...], "asOfDate": "2026-11-09", "pageSize": 200, "truncated": false}
```

These have **no continuation token**. When `truncated=true`, narrow the filters or raise `pageSize`;
the remainder is not fetchable from the same call. `/stores` uses `pageSize` instead of `limit` for
historical reasons. `/retail/inventory/reconciliation` returns a bounded report
(`status`, `items`, `truncated`, no token) rather than `PageResponse`.

`RETAIL_DEFAULT_PAGE_SIZE` is the default for offset-paginated lists; PO lines are the exception with
a literal default of 200. Page caps are not uniform: `RETAIL_MAX_PAGE_SIZE` covers stores, inventory,
replenishment, movements, and reconciliation; dead letters, runs, purchase orders/history, and
supplier transmissions cap at 200; PO lines at 1,000; proposals at 5,000.

A retail read whose owned dataset/table is missing returns `503 DATASET_NOT_PROVISIONED` (not
retryable; run the schema/seed job). When no snapshot or ledger data exists at all, the read returns
`503 DATA_NOT_READY` (retry after the next load).

- Offset pagination is deliberate for v1. Deep pages re-scan; keyset pagination on
  `(sort key, id)` is the planned follow-up for the movement ledger, dead letters, and proposals.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/retail/stores` | Active stores |
| `GET` | `/api/v1/retail/inventory?storeId=&asOfDate=&category=&riskLevel=` | Inventory positions with demand cover/risk |
| `GET` | `/api/v1/retail/replenishment?storeId=&asOfDate=` | Prioritized replenishment worklist |
| `GET` | `/api/v1/retail/dashboard?storeId=&asOfDate=` | Inventory, sales, shipment, promotion KPIs |
| `GET` | `/api/v1/retail/inventory/movements?storeId=&productId=&from=&to=&limit=&pageToken=` | Append-only movement ledger |
| `GET` | `/api/v1/retail/inventory/reconciliation?storeId=&asOfDate=&tolerance=` | Ledger-to-snapshot drift |
| `POST` | `/api/v1/retail/inventory/projection?mode=INCREMENTAL|FULL&lookbackDays=` | Refresh the on-hand projection |
| `POST` | `/api/v1/retail/replenishment-runs` | Create/replace a durable replenishment run |
| `GET` | `/api/v1/retail/replenishment-runs?storeId=&limit=&pageToken=` | List recent runs |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}` | Run status and totals |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}/proposals` | Prioritized proposals |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/approve` | Approve |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/reject` | Reject |
| `POST` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/order` | Mark ordered |
| `GET` | `/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}/history` | Decision history |
| `POST` | `/api/v1/retail/purchase-orders` | Create a draft PO from approved proposals |
| `GET` | `/api/v1/retail/purchase-orders?limit=&pageToken=` | List purchase orders |
| `GET` | `/api/v1/retail/purchase-orders/{poId}` | Get one PO |
| `GET` | `/api/v1/retail/purchase-orders/{poId}/lines` | PO lines |
| `POST` | `/api/v1/retail/purchase-orders/{poId}/submit` | Submit a draft PO |
| `POST` | `/api/v1/retail/purchase-orders/{poId}/cancel` | Cancel a draft PO |
| `GET` | `/api/v1/retail/purchase-orders/{poId}/history` | PO lifecycle audit |
| `POST` | `/api/v1/retail/events` | Accept a movement event asynchronously |

`asOfDate` defaults to the latest inventory snapshot. Dates are business dates (`YYYY-MM-DD`).
`riskLevel` is one of `STOCKOUT`, `CRITICAL`, `WATCH`, `NO_DEMAND`, `HEALTHY`.
Page sizes on the retail read endpoints are clamped to `RETAIL_MAX_PAGE_SIZE` (default 200); inventory
and replenishment pages set `truncated=true` when more rows remain. Offset lists use their own caps
described under Pagination.

## Read path

Every retail read goes through `RetailSqlExecutor` in `retail.query`; only that package builds a
`QueryRequest`. The executor substitutes the configured project/dataset into fixed SQL under
`sql/retail`, applies `RETAIL_MAX_BYTES_BILLED`, labels jobs `surface=retail`, and maps a missing
dataset/table to `503 DATASET_NOT_PROVISIONED`. Retail reads have their own concurrency pool (half
`BIGQUERY_MAX_CONCURRENT_QUERIES`, minimum 1), so operator `/query` traffic cannot starve them.
Shared formulas live in `sql/retail/partials` and are included with `{{include:name}}`; nesting is
limited to `MAX_INCLUDE_DEPTH = 2`.

## Data model

```
stores ──< inventory_snapshots >── products ── suppliers
   │                                  │
   └──< sales >───────────────────────┘
   └──< shipments >──────────────────┘
promotions ──< promotion_products >── products

inventory_movements  (append-only source of truth; event_id is the idempotency key)
        │
        └── inventory_movements_deduped
                │
                └── inventory_on_hand_projection (incremental table)
                        │
                        └── inventory_on_hand_daily (stable read view)

replenishment_runs ──< replenishment_proposals
```

- **`inventory_movements` is the source of truth.** It is append-only. On-hand is a projection, not
  a mutable row. Corrections are reversing movements.
- Read endpoints source on-hand from `inventory_on_hand_daily` (the ledger projection), falling back
  to the snapshot when no ledger row exists, and take in-transit, reserved, safety stock, reorder
  point, and unit cost from the latest snapshot.
- Grain: one movement per business event; one row per store/product/day for inventory snapshots;
  one row per sale/shipment line.
- `sales`, `inventory_snapshots`, and `inventory_movements` are partitioned by business date.
  `sales` and `inventory_snapshots` cluster by `store_id, product_id`; `inventory_movements` clusters
  by `event_id, store_id, product_id`.
- Money/NUMERIC is serialized as a JSON string; quantities are integers.
- `unit_cost` on facts is the point-in-time value; keep it for reproducible valuation.

## Ledger semantics

- The sink coalesces concurrent writes into one array-parameter `MERGE` keyed by
  `(event_id, business_date)`. `eventId` is the caller's idempotency key; `business_date` is derived
  from `occurredAt` in `RETAIL_BUSINESS_ZONE`, so changing the zone or replaying with a different
  `occurredAt` creates a second ledger row (reads still dedupe by `event_id`). In batch mode the
  inserted/duplicate outcome is batch-level: if any row inserts, every caller in that batch sees
  `INSERTED`, and a batch with no inserts is `DUPLICATE`. A retryable failure fails the whole batch;
  deterministic row failures use a bounded per-row fallback. `RETAIL_LEDGER_BATCH_*` controls batch
  size, linger, queue bound, retained-payload bytes
  (`RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES`), and caller wait.
- `SALE` is stored as a negative delta; `RETURN`, `SHIPMENT`, `OPENING_BALANCE`, and positive
  `INVENTORY_ADJUSTMENT` are positive; `INVENTORY_ADJUSTMENT` may be signed.
- Deduplication partitions by `event_id` and breaks ties with `(ingested_at, event_time)` in both the
  deduped view and the inline read/projection queries.
- `occurredAt` is event time; `business_date` is derived in `RETAIL_BUSINESS_ZONE` (default `UTC`).
- `SHIPMENT` means a receipt into on-hand. The snapshot loader must exclude already-received
  shipments from `in_transit_units`; emitting a dispatch as `SHIPMENT` would double count available
  units. A separate dispatch/receipt event pair is the next supply-chain slice.
- The consumer retries transient BigQuery failures, sends permanent failures to the dead-letter
  topic immediately, and acknowledges only after the ledger commit (or a successful DLQ publish).
  The event-age window is enforced at intake; replaying an older dead letter is allowed.
- Reconciliation compares the ledger through the latest snapshot at or before `asOfDate` to that
  snapshot; drift is a data-quality signal, not an automatic correction.
- Post exactly one `OPENING_BALANCE` per store/product before go-live. It is the ledger baseline;
  reconciliation assumes the cumulative ledger starts with it. Do not post a second opening balance
  for the same store/product.

## On-hand projection

`inventory_on_hand_projection` is the maintained read model. `inventory_on_hand_daily` is a stable
view over it, so read SQL does not care whether the last refresh was full or incremental.

- `POST /api/v1/retail/inventory/projection?mode=INCREMENTAL&lookbackDays=7` recomputes a lookback
  window so late-arriving events are corrected.
- `mode=FULL` rebuilds every movement date and removes projection rows that no longer exist in the
  ledger (`WHEN NOT MATCHED BY SOURCE THEN DELETE`).
- `RETAIL_PROJECTION_ENABLED=true` runs the incremental refresh on a schedule
  (`RETAIL_PROJECTION_INTERVAL_MS`, default 15 minutes).
- The seed runs `60_inventory_projection.sql` after the ledger so the demo starts with a populated
  projection.
- In production, run the projection as a Cloud Run Job or a scheduler with the writer identity; keep
  it off the request path.

## KPI definitions (v1)

- **Available** = ledger on-hand + in-transit − reserved.
- **Average daily units** = units sold over the demand window ÷ window days.
- **Days of supply** = available ÷ average daily units; null when demand is zero.
- **Risk**:
  - `STOCKOUT` if on-hand ≤ 0
  - `NO_DEMAND` if average daily units = 0 (evaluated before `CRITICAL`)
  - `CRITICAL` if available ≤ safety stock or days of supply < lead time
  - `WATCH` if available ≤ reorder point
  - otherwise `HEALTHY`
- **Effective reorder point** = max(stored reorder point, ceil(avg daily × lead time) + safety stock).
- **Recommended units** = ceil((target cover × avg daily − available) / case pack) × case pack,
  raised to the minimum order quantity, only when below the effective reorder point.
- **Dashboard** counts stockouts, low-stock SKUs, 28-day units/sales, open late shipments,
  delivered-late lines, and active promotions.

## Replenishment runs

The live `/api/v1/retail/replenishment` endpoint remains a quick worklist. The durable decision path
is a run:

- `POST /api/v1/retail/replenishment-runs` with `{runId?, storeId, asOfDate?, demandWindowDays?,
  targetCoverDays?}`.
- Business-date guards and event business dates use `RETAIL_BUSINESS_ZONE` (default `UTC`); set it
  to the store calendar when the deployment serves one region.
- A run is bounded to one store and at most 5,000 proposals; if more are eligible, the run keeps
  the top 5,000 by priority and reports `proposalCount=5000` (no overflow marker), so alert on that
  value. Creation holds a Postgres run lease
  keyed by `runId`, so concurrent requests for the same run are rejected instead of interleaving.
  Production multi-store/backfill work
  should move to a Cloud Run Job or Workflow.
- `runId` is the idempotency key. Re-running the same `runId` clears the old proposals, recomputes,
  and replaces the run metadata **only while every proposal is still `PROPOSED`**; once any decision
  exists the request is `409` and a new `runId` is required. A new `runId` creates a new decision
  record.
- The run status is `RUNNING`, `COMPLETED`, or `FAILED`. A failure records a safe error class, not
  SQL text.
- Proposals store the inputs and the decision: available units, average daily units, days of supply,
  lead time, effective reorder point, recommended units, order value, and a reason
  (`STOCKOUT`, `BELOW_LEAD_TIME`, `BELOW_SAFETY_STOCK`, `BELOW_REORDER_POINT`).
- The `run_id` is stored on every proposal, and the run row stores `proposal_count` and
  `total_order_value`.

### Proposal states

- `PROPOSED` → `APPROVED` or `REJECTED`
- `APPROVED` → `ORDERED` or `REJECTED`
- `ORDERED` and `REJECTED` are terminal.
- Every transition requires `expectedVersion`. The server updates only when the stored `version`
  and `status` match; otherwise it returns `409 CONFLICT`.
- `order` requires an `orderReference`; this records an external order marker, not a generated
  purchase order.
- Every successful transition appends to `replenishment_proposal_decisions` with the from/to state,
  expected version, actor, note, reference, and timestamp.
- The proposal update and its decision insert are one BigQuery `BEGIN TRANSACTION` script that also
  asserts the run is `COMPLETED`; a completed decision cannot be half-applied.
- Re-running a `runId` that already has non-`PROPOSED` decisions is rejected with `409`; use a new
  `runId` for a new decision cycle.

Supplier transmission is a separate Postgres outbox workflow (see Purchase orders); receiving and
invoice matching are not implemented. The run/proposal/decision/purchase-order tables are the audit
boundary those workflows attach to.

## Purchase orders

- `POST /api/v1/retail/purchase-orders` with `{idempotencyKey?, runId, storeId, supplierId,
  expectedDeliveryDate?, note?}`.
- One PO is scoped to one run/store/supplier. If `idempotencyKey` is omitted, the server derives
  `runId:storeId:supplierId`, so a retry returns the existing PO instead of creating a duplicate.
- A PO is bounded to 1,000 lines. Each line records the approved proposal version it copied
  (`source_proposal_version`, internal to the store), so the creation snapshot is auditable after
  later proposal decisions.
- `idempotencyKey` identifies the run/store/supplier/expected-delivery request. Reusing it with a
  different shape returns `409` instead of silently returning the first order. A partial unique
  index also enforces one live PO per run/store/supplier, so a different key cannot duplicate the
  order; a cancelled scope can be replaced.
- Purchase orders, lines, and lifecycle events live in Postgres; approved proposals are read from
  BigQuery at creation time.
- Only `APPROVED` proposals are copied. A run with no approved lines for that supplier gets `409`.
- Lines copy product, SKU, name, ordered units, and line value from the proposal.
- `DRAFT` can be submitted or cancelled with `expectedVersion`; both transitions append to
  `purchase_order_events`.
- Submission updates the Postgres PO and inserts the supplier outbox row in one transaction. The
  `transmit` endpoint is an idempotent manual trigger for the same outbox row.
- `submitted` accepts an optional `supplierReference`. This is the boundary for the EDI/API
  transmission adapter; it does not create a real supplier order.
- Receiving, partial receipts, and invoice matching are deferred.

## Assumptions and limits

- Single tenant, single currency, daily batch.
- The read API is read-only. Event intake writes an append-only ledger asynchronously; it never
  writes inline on the request path.
- The read endpoints default to the latest snapshot date so snapshot attributes (safety stock, cost)
  are coherent. Ledger movements dated after that snapshot are visible through the movements
  endpoint (which defaults `to` to the latest ledger date) and in on-hand reads after the next
  snapshot refresh.
- Synthetic data is not accounting-coherent with shipments; KPI tests use fixtures.
- The seeded ledger is derived from the synthetic snapshots so reconciliation is meaningful before a
  real POS/receiving feed exists. That bridge is demo-only.
- Sales are censored by stockouts; the demand window is a baseline, not true demand.
- No causal promo lift, ML forecast, supplier scorecard, real EDI/API supplier transmission,
  receiving/invoice matching, or multi-store run orchestration yet. A durable Postgres outbox and a
  local demo `SupplierGateway` are implemented.
- BigQuery is the analytics store, not a real-time POS/inventory system.

## Demo

```bash
make retail-schema
make retail-seed
make up
export KEY=$(grep '^API_KEY=' .env | cut -d= -f2)
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/dashboard?storeId=ST-001' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/replenishment?storeId=ST-001' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/movements?storeId=ST-001&limit=20' | jq
curl -s -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/reconciliation?asOfDate=2026-11-09' | jq
curl -s -X POST -H "X-API-Key: $KEY" \
  'localhost:8080/api/v1/retail/inventory/projection?mode=INCREMENTAL' | jq
```

Event intake:

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST localhost:8080/api/v1/retail/events \
  -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"evt-1\",\"eventType\":\"SALE\",\"occurredAt\":\"$NOW\",
       \"storeId\":\"ST-001\",\"productId\":\"SKU-0001\",\"quantity\":2,
       \"payload\":{\"sourceDocumentId\":\"POS-1\"}}" | jq
```

`INVENTORY_ADJUSTMENT` accepts a signed quantity; `SALE`, `RETURN`, and `SHIPMENT` require a
positive quantity. The local broker is bounded in-memory with a local relay; production uses Pub/Sub
with a DLQ subscription. See [docs/messaging](../messaging/README.md).
