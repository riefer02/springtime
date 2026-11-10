# ADR 0005 — BigQuery schema migrations, views, and rollback

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The service owns no relational application schema today. BigQuery is the live data plane, and its
tables/views may evolve independently of this Java service. BigQuery DDL is **not transactional
across objects**, has no unique constraints or advisory locks, and its time-travel window is finite.

We still need a tenable, forward-only migration policy before the first owned dataset/view lands.

## Decisions

### Tooling

- **Do not use Flyway or Liquibase for BigQuery.** Both require the Simba JDBC driver, add weak
  transaction/lock semantics, and split multi-statement scripts unreliably. Flyway Community’s data
  limit is disqualifying for a warehouse.
- **Do not use Terraform for table evolution.** Terraform owns datasets, IAM, locations, budgets,
  and quotas; it has no ordered migration ledger/checksums and can replace objects.
- **Do not use Dataform/dbt for DDL history.** They are transformation/model frameworks. They are
  appropriate later for analytical joins/views in a separate warehouse layer.
- **Use a small, forward-only runner built on the existing BigQuery Java client** when the project
  owns DDL. Keep it in this repo, run it as a separate deploy job, and never on application startup.

### Layout (when implemented)

```text
db/
├── bootstrap/                 # one-time control dataset/ledger bootstrap (admin/Terraform)
├── migration/
│   └── V20261109_001__create_orders.sql
├── repeatable/
│   └── R__api_orders_v1.sql   # CREATE OR REPLACE VIEW for API-facing views
└── undo/                      # manual runbook artifacts; never auto-run
```

- Versioned files are immutable after apply. One top-level DDL statement per file wherever possible.
- Repeatable files (`R__*.sql`) run after all versioned migrations; a changed checksum re-runs them.
- **API-facing views live in repeatable migrations**, not in Java and not in a hidden ETL job.
- Analytical transforms/joins move to Dataform or dbt later, in a separate `warehouse/` tree or repo.

### Ledger

A `springtime_meta._schema_migrations` table records `version`, `description`, `script`, `checksum`,
`status` (`RUNNING`/`SUCCESS`/`FAILED`/`BASELINED`), `run_id`, `job_id`, `applied_by`, timestamps,
and backup references.

- No primary key is possible; the loader rejects duplicates and checksums mismatch.
- `RUNNING`/`FAILED` blocks the next apply until an operator verifies and repairs the ledger.
- Applied files are immutable; checksum mismatches are a hard failure.
- The ledger is the idempotency mechanism; SQL-level `IF NOT EXISTS` is only crash-recovery aid.

### Concurrency

- Run exactly one migration job per environment (`Cloud Run Job` with `tasks=1`, `parallelism=1`,
  `max-retries=0`, or a CI concurrency group).
- The ledger `RUNNING` row is a second-line guard, **not** a distributed lock.
- If multiple deploy paths appear, add a GCS lease using `ifGenerationMatch=0`; do not pretend the
  ledger is a mutex.

### Rollback / roll-forward

1. **App rollback first** while the schema is expanded and backward-compatible.
2. **Forward-fix migration** recorded in the ledger is the default compensation.
3. **Snapshot/clone before destructive DDL** (`springtime_backup`); record the reference in the
   ledger. Time travel (7 days) is emergency data recovery, not a schema rollback.
4. **Never auto-run `down` scripts.** Undo is a human-approved runbook, not CI.
5. Views/routines rollback = redeploy the previous SQL; time travel does not cover them.
6. Use **expand/contract** for renames, type changes, drops, partitioning/clustering changes, and
   required-column additions.
7. BigQuery cannot atomically roll back multiple DDL statements. A failed multi-statement script
   leaves earlier statements applied; prefer one statement per migration.

### Source of truth

- Migration SQL = canonical history of **intended** DDL.
- Live BigQuery metadata = canonical **observed** schema for the API.
- Committed OpenAPI = canonical **wire** contract.
- `schemaHash` = derived cache/client version of the column tree.
- A committed warehouse contract may later be generated from live metadata; it is a tripwire, not a
  replacement for BigQuery.

## Consequences

- Owned DDL exists under `db/retail/schema` and is applied idempotently by `make retail-schema`.
  The ADR's migration runner and `springtime_meta` ledger described above are **not implemented**
  yet; until they are, applied DDL has no recorded checksum or status.
- Once needed, the runner is a small module with plan/apply/verify/status/baseline/repair commands
  and tests; it is not a platform.
- The runtime API service account stays read-only. A separate migrator identity has dataset-scoped
  editor rights.
- `SPRINGDOC_ENABLED`, API-facing views, and migrations must be reviewed together when they change.
