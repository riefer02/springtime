# ADR 0015 — Postgres control plane and durable supplier outbox

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0014 (purchase orders), ADR 0005 (migrations)

## Context

Supplier transmission needs durable, transactional state: enqueue, claim, retry, acknowledge, and
fail. BigQuery is an analytical warehouse, not a queue or lock manager. It cannot provide
`FOR UPDATE SKIP LOCKED`, unique constraints, or atomic “change PO state + insert outbox row”.

## Decisions

- **Postgres owns the control plane.** Purchase orders, lines, lifecycle events, and
  `supplier_transmissions` live in Postgres. Replenishment run/proposal state remains in BigQuery
  until that migration is justified.
- **BigQuery remains the analytical plane.** Ledger, projections, demand, and retail read models stay
  in BigQuery.
- **Flyway + `JdbcClient`, no ORM.** Migrations are versioned SQL in
  `src/main/resources/db/migration`. Postgres access is explicit SQL. No JPA/Hibernate.
- **Durable outbox.** `submit` inserts an idempotent outbox row keyed by `po_id` in the same
  transaction as the PO transition; `POST /purchase-orders/{poId}/transmit` is a manual trigger for
  the same row. A worker claims due rows with `FOR UPDATE SKIP LOCKED`, reclaims stale `SENDING`
  rows, calls `SupplierGateway`, and records `SENT`/`ACKED`/`FAILED`. The payload is a control-owned
  contract that includes every PO line.
- **Retry policy.** Only transient failures retry, with bounded exponential backoff + jitter.
  Permanent failures go to `FAILED`; an admin can retry explicitly.
- **Supplier adapter is a port.** The local adapter is a demo. EDI/API adapters are separate
  implementations; the schema is an external contract, not our domain model.
- **No package cycle.** `control` owns the outbox and the reader port; the retail store adapts its
  PO model to that port. `control` imports no `retail` package.
- **Ack semantics.** `SENT` is not `ACKED`. Acknowledgement is an explicit state transition.

## Consequences

- The outbox is durable and race-safe across workers.
- Postgres must be provisioned and migrated separately; local Compose can run it.
- PO state and the outbox live in Postgres, so submit updates the PO and inserts the outbox row in
  one transaction. Replenishment proposal state is still in BigQuery; that is the remaining
  cross-store read for PO creation.
- Real supplier EDI/API transmission, ASN ingestion, and receiving remain future slices.
