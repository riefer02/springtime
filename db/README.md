# Database migrations

The project owns two schemas:

- **Postgres control plane** — Flyway migrations in `src/main/resources/db/migration`
  (`V1__supplier_outbox.sql`, `V2__purchase_orders.sql`,
  `V3__supplier_outbox_hardening.sql`, `V4__purchase_order_constraints.sql`,
  `V5__control_run_leases.sql`, `V6__purchase_order_line_proposal_version.sql`,
  `V7__purchase_order_scope_unique.sql`).
- **BigQuery analytical plane** — versioned retail DDL in `db/retail/schema`, applied by
  `make retail-schema`.

Both are forward-only. Postgres migrations are immutable once applied. Production runs Flyway as a
separate deploy job, not on app startup; local Compose enables it for convenience.

See [ADR 0005](../docs/adr/0005-bigquery-schema-migrations.md) and
[ADR 0015](../docs/adr/0015-postgres-control-plane-and-supplier-outbox.md). The current BigQuery
schema is intentionally small:

```text
db/retail/schema/
├── 001_inventory_movements.sql    # append-only ledger
├── 002_inventory_views.sql        # dedupe view
├── 003_inventory_projection.sql   # incremental on-hand projection table
├── 004_inventory_on_hand_daily.sql# stable read view
├── 005_retail_event_dead_letters.sql # durable DLQ/replay records
├── 006_replenishment_runs.sql     # runs + proposals
├── 007_replenishment_proposal_decisions.sql # proposal states + audit
```

Rules:

- Forward-only; applied files are immutable.
- One DDL statement per migration where possible.
- Postgres history lives in `flyway_schema_history`. BigQuery DDL is re-applied idempotently by
  `make retail-schema`; there is currently **no** `springtime_meta` ledger or checksum enforcement
  (ADR 0005's ledger is unimplemented).
- Run as a separate deploy job, never on app startup in production.
- Snapshot/clone before destructive changes; use expand/contract.
- API-facing views belong in repeatable migrations; analytical joins belong in Dataform/dbt later.
- The runtime API service account stays read-only for BigQuery; the migrator is a separate identity.
