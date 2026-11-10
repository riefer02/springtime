# ADR 0008 — Retail inventory & replenishment copilot v1

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The generic BigQuery API now needs a domain that demonstrates real retail analytics without becoming
an order-management system or a fake ORM. The target is a read-only inventory/replenishment
copilot over a deterministic demo dataset, generic enough for any retail domain and free of brand
names.

## Decisions

- **Read-only v1.** Four endpoints: stores, inventory positions, replenishment recommendations,
  and a store dashboard. No purchase-order writes, transfers, or approvals.
- **BigQuery is the analytical store.** A small star-ish model:
  `stores`, `products`, `suppliers`, `distribution_centers`, `sales`, `inventory_snapshots`,
  `shipments`, `promotions`.
- **Physical design:** facts partitioned by business date and clustered by store/SKU; every query
  filters its partition date. `require_partition_filter` and a retail byte ceiling guard cost.
- **Fixed, parameterized SQL.** No request-supplied SQL, dataset, or table names. SQL lives in
  `src/main/resources/sql/retail/` and executes through the existing `BigQueryService` guard,
  semaphore, timeout, byte cap, labels, and error mapping.
- **Typed mapping.** BigQuery rows map to records; money/NUMERIC stays a string on the wire;
  dates are ISO business dates.
- **Auth:** `/api/v1/retail/**` requires `ROLE_API` or `SCOPE_retail.read`. The operator `/query`
  surface remains separate.
- **Deterministic demo seed:** pure GoogleSQL CTAS with `FARM_FINGERPRINT`, fixed anchor date,
  10 stores, 24 SKUs, 180 days of sales, 90 days of inventory, shipments and promotions. Run with
  `make retail-seed`; never on app startup.
- **KPI semantics** are documented in `docs/retail/README.md`:
  available = on-hand + in-transit − reserved; days of supply; risk bands; effective reorder point;
  case-rounded recommended quantity.
- **Additive contract:** new paths only; OpenAPI v1 remains valid. `make openapi` regenerates the
  committed contract.

## Consequences

> **Amendment:** ADRs 0012–0015 add replenishment decisions, purchase orders, and the
> Postgres supplier outbox. v1 itself was read-only.


- The demo is compelling without big data: ~60k sales rows and ~22k snapshots are enough to show
  partitioning, clustering, cost control, risk, and replenishment.
- Promotion lift detail, supplier scorecard, ML forecasting, async replenishment runs, dashboards,
  and a Postgres control plane are deferred.
- The seed is not accounting-coherent across sales/inventory/shipments; KPI logic is tested with
  fixtures. Re-running the seed replaces demo tables and is not a migration ledger.
- BigQuery is not a real-time POS or order-management database; the API is an analytical read
  surface.
