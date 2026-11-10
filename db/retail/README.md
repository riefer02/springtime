# Retail demo dataset

Deterministic, generic retail data for the inventory/replenishment copilot. No brand names.

## What it creates

Schema (`db/retail/schema`):

- `001_inventory_movements` — append-only ledger, partitioned by `business_date`, clustered by
  `store_id, product_id`
- `002_inventory_views` — `inventory_movements_deduped`
- `003_inventory_projection` — `inventory_on_hand_projection` table
- `004_inventory_on_hand_daily` — stable read view over the projection
- `005_retail_event_dead_letters` — durable dead-letter records and replay state
- `006_replenishment_runs` — run metadata and prioritized proposals
- `007_replenishment_proposal_decisions` — proposal state columns and append-only decision audit

Postgres control-plane schema (`src/main/resources/db/migration`):

- `V1__supplier_outbox.sql` — durable supplier transmissions
- `V2__purchase_orders.sql` — purchase orders, lines, lifecycle events
- `V3__supplier_outbox_hardening.sql` — ack/retry attribution, status checks, stale-claim index
- `V4__purchase_order_constraints.sql` — PO status and line-unit checks
- `V5__control_run_leases.sql` — cross-instance replenishment run leases
- `V6__purchase_order_line_proposal_version.sql` — auditable proposal snapshot per PO line
- `V7__purchase_order_scope_unique.sql` — one purchase order per run/store/supplier

Seed (`db/retail/seed`):

- `suppliers`, `distribution_centers`, `stores`, `products`
- `sales` (60k lines, 180 days)
- `inventory_snapshots` (10 stores × 24 SKUs × 90 days)
- `shipments` (2k lines)
- `promotions`, `promotion_products`
- `inventory_movements` derived from snapshot deltas so reconciliation has a meaningful baseline
- `inventory_on_hand_projection` rebuilt from the ledger

All seed tables are created with `CREATE OR REPLACE TABLE ... AS SELECT`. The dataset is disposable;
never run these scripts against a shared/production dataset.

Facts are partitioned and clustered:

- `sales`: partition `sale_date`, cluster `store_id, product_id`
- `inventory_snapshots`: partition `snapshot_date`, cluster `store_id, product_id`
- `inventory_movements`: partition `business_date`, cluster `store_id, product_id`
- `shipments`: partition `expected_arrival_date`, cluster `store_id, product_id, supplier_id`
- `replenishment_runs`: partition `as_of_date`, cluster `store_id`
- `replenishment_proposals`: partition `as_of_date`, cluster `run_id, store_id, product_id`

## Run

```bash
export GOOGLE_CLOUD_PROJECT=your-project-id
export RETAIL_DATASET=retail_demo
export BIGQUERY_LOCATION=US

make retail-schema   # forward-only ledger DDL + views
make retail-seed     # synthetic dims/facts + ledger bridge
```

`make retail-seed` applies the schema and runs every `seed/*.sql` file with `bq`. It requires
`gcloud`/`bq` auth and BigQuery permissions. The app runs Postgres Flyway migrations on startup when
`FLYWAY_ENABLED=true` (local Compose); production runs them as a separate deploy job. The app never
runs BigQuery seeds on startup. If `bq` fails under a newer Python, set
`CLOUDSDK_PYTHON=/usr/bin/python3` (the Makefile does this by default).

## Validate

```bash
bq query --use_legacy_sql=false --location=US \
  'SELECT COUNT(*) AS stores FROM `PROJECT.retail_demo.stores`'
```

The seed is deterministic for the fixed anchor `DATE '2026-11-09'`; re-running it replaces the demo
tables. The ledger bridge deletes only `source_system='retail-seed'` rows, so it does not wipe
real movements. Do not use this as a migration ledger.
