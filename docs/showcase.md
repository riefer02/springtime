# Showcase walkthrough

A short, honest tour for a reviewer (human or agent). It assumes a running deployment with the
deterministic seed loaded. The built-in UI is **Swagger UI** at `/swagger-ui/index.html`; the API
contract is `/openapi/springtime-v1.json` (authenticated). This guide is the map.

## Hand-off package

Send a reviewer:

| Item | Value |
| --- | --- |
| Base URL | `https://<service>.run.app` |
| Read-only key | `springtime-api-key` (Secret Manager) |
| OpenAPI | `GET /openapi/springtime-v1.json` with `X-API-Key` |
| Demo console | `/demo/index.html` (paste the read-only key) |
| API explorer | `/swagger-ui/index.html` (use **Authorize** with `X-API-Key`) |
| This guide | `docs/showcase.md` |
| Operations | `docs/operations/README.md` |

Everything below is read-only and safe with the read-only key. The write arc in the next section
requires the operator key (`springtime-admin-key`); run it once before handing over if you want the
reviewer to see durable runs and purchase orders.

## Start with the console

Open `/demo/index.html`, paste the read-only key into the key panel, and pick a store. The page shows
store KPIs, the inventory-risk worklist, replenishment recommendations, recent runs, and purchase
orders. Use the tabs to avoid endless scrolling, search and sort tables, filter inventory by risk,
and open a run to inspect its proposals. It is read-only by construction (no write calls), stores the
key in session storage, and talks only to the same origin.

Swagger remains the complete API reference; the console is the fast path for a reviewer. Details:
[demo-console.md](demo-console.md).

## 10-minute read-only tour

```bash
BASE=https://<service>.run.app
KEY=<read-only key>
auth=(-H "X-API-Key: $KEY")
```

1. **Liveness and readiness**
   ```bash
   curl -s "$BASE/actuator/health/liveness"
   curl -s "$BASE/actuator/health/readiness"
   ```
   Liveness proves the process; readiness includes Postgres and a BigQuery dry run. A credential or
   dataset problem shows here, not in a 500.

2. **Stores and inventory risk**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/stores?pageSize=10" | jq
   curl -s "${auth[@]}" "$BASE/api/v1/retail/inventory?storeId=ST-001&riskLevel=CRITICAL" | jq
   ```
   Look for: the page envelope, `asOfDate`, `riskLevel`, `daysOfSupply`, and the computed
   `projectedStockoutDate`.

3. **Replenishment worklist**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/replenishment?storeId=ST-001" | jq
   ```
   This is the planner's ranked action list: recommended units, order value, supplier, reason.

4. **Dashboard KPIs**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/dashboard?storeId=ST-001" | jq
   ```
   Stockouts, low-stock SKUs, 28-day sales, late shipments, promotions.

5. **Ledger and reconciliation**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/inventory/movements?storeId=ST-001&limit=20" | jq
   curl -s "${auth[@]}" "$BASE/api/v1/retail/inventory/reconciliation?storeId=ST-001&tolerance=0" | jq
   ```
   The ledger is append-only; reconciliation compares it to the snapshot projection.

6. **Durable decisions (if the operator arc has run)**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/replenishment-runs?limit=5" | jq
   curl -s "${auth[@]}" "$BASE/api/v1/retail/purchase-orders?limit=5" | jq
   ```
   Follow `nextPageToken`; a complete page has no token. Inventory/replenishment are ranked top-N
   reads with `truncated`, not token pages.

7. **Error contract**
   ```bash
   curl -s "${auth[@]}" "$BASE/api/v1/retail/purchase-orders?pageToken=not-a-token" | jq
   curl -s "${auth[@]}" "$BASE/api/v1/retail/inventory?storeId=DOES-NOT-EXIST" | jq
   ```
   Expect `400 INVALID_REQUEST` and `404 RESOURCE_NOT_FOUND` with `code`, `requestId`, `retryable`.

## Operator arc (run once, then hand over the read key)

Scripted version: `make demo-seed BASE_URL=https://... ADMIN_API_KEY=...` (requires `jq`). It picks
the first proposal, approves it, creates and submits a PO, and enqueues the outbox. Manual curl is
below if you want to narrate it.

Use the admin key. This creates a decision record, approves one proposal, builds a draft PO, submits
it, and enqueues the supplier outbox:

```bash
ADMIN=<admin key>
a=(-H "X-API-Key: $ADMIN" -H 'Content-Type: application/json')

RUN=$(curl -s "${a[@]}" -X POST "$BASE/api/v1/retail/replenishment-runs" \
  -d '{"storeId":"ST-001"}' | jq -r .runId)

# Pick the first proposal; use its productId and version.
P=$(curl -s -H "X-API-Key: $ADMIN" "$BASE/api/v1/retail/replenishment-runs/$RUN/proposals?limit=1")
PRODUCT=$(echo "$P" | jq -r '.items[0].productId')
VERSION=$(echo "$P" | jq -r '.items[0].version')

curl -s "${a[@]}" -X POST \
  "$BASE/api/v1/retail/replenishment-runs/$RUN/proposals/$PRODUCT/approve" \
  -d "{\"expectedVersion\":$VERSION,\"note\":\"demo approval\"}" | jq

PO=$(curl -s "${a[@]}" -X POST "$BASE/api/v1/retail/purchase-orders" \
  -d "{\"runId\":\"$RUN\",\"storeId\":\"ST-001\",\"supplierId\":\"SUP-001\",\"expectedDeliveryDate\":\"2026-12-01\"}")
PO_ID=$(echo "$PO" | jq -r .poId)
PO_VERSION=$(echo "$PO" | jq -r .version)

curl -s "${a[@]}" -X POST "$BASE/api/v1/retail/purchase-orders/$PO_ID/submit" \
  -d "{\"expectedVersion\":$PO_VERSION,\"supplierReference\":\"DEMO-1\"}" | jq
curl -s "${a[@]}" -X POST "$BASE/api/v1/retail/purchase-orders/$PO_ID/transmit" | jq
curl -s -H "X-API-Key: $ADMIN" "$BASE/api/v1/retail/supplier-transmissions" | jq
```

What this proves: optimistic concurrency (`expectedVersion`), the proposal state machine, a PO built
from approved proposals, atomic PO submit + outbox enqueue, and a visible transmission. The bundled
supplier gateway is a demo adapter, not real EDI.

## Agent hand-off

Give the reviewer's agent: base URL, read-only key, and permission to read `/openapi/...` plus
`docs/`. A useful prompt:

> You have read-only API access to a retail supply-chain service. Read
> `/openapi/springtime-v1.json` and `docs/showcase.md`, then exercise the read endpoints for ST-001.
> Report: (1) whether the API contract is understandable without reading Java, (2) any response that
> is ambiguous or missing context, (3) error shapes you would struggle to handle, (4) one thing you
> would add.

## What to look for

- Can a planner explain *why* a product is on the worklist from the response alone?
- Are the page/truncation rules obvious from the payload?
- Is every error actionable (`code`, `retryable`, `requestId`)?
- Does the read-only key actually prevent writes?
- Is `asOfDate` consistent across inventory, replenishment, and dashboard?

## What this is not

- Not production-ready for untrusted clients: no per-key rate limit, no query idempotency, keys
  identify a client rather than a person.
- Not a real supplier integration: the outbox and state machine are real; the gateway is a local demo.
- Single-store replenishment; no receiving, invoice matching, forecasting, or multi-store runs.
