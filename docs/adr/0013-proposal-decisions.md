# ADR 0013 — Proposal decisions and optimistic concurrency

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0012 (replenishment runs/proposals)

## Context

A proposal is a recommendation, not a commitment. Planners need to approve, reject, or mark it
ordered without races, and reviewers need an audit trail. BigQuery is the analytical store, not an
OLTP transaction manager, so the concurrency and audit model must be explicit.

## Decisions

- **State machine:** `PROPOSED` → `APPROVED` | `REJECTED`; `APPROVED` → `ORDERED` | `REJECTED`.
  `ORDERED` and `REJECTED` are terminal.
- **Optimistic concurrency:** every proposal has a `version`. A transition requires
  `expectedVersion`. The conditional `UPDATE` matches `version` and `status`; zero affected rows is
  `409 CONFLICT`.
- **Append-only audit:** every successful transition inserts into
  `replenishment_proposal_decisions` with from/to state, expected version, actor, note, order
  reference, and timestamp.
- **Order marker:** `order` requires an external `orderReference`; it records that an order was
  placed, not that this service created one.
- **Re-run guard:** a `runId` with any non-`PROPOSED` decision cannot be replaced. Use a new
  `runId` for a new decision cycle.
- **Idempotent same-state requests:** repeating the current state with the matching version returns
  the current proposal without a new decision record.

## Consequences

> **Amendment:** approved proposals can now become purchase orders (ADR 0014) and durable supplier
transmissions (ADR 0015).

- Two planners cannot approve the same version twice; the second gets `409`.
- The audit trail is queryable and independent of application logs.
- The proposal transition and its append-only decision row are one BigQuery multi-statement
  transaction (`BEGIN TRANSACTION` … `ASSERT` … `UPDATE` … `INSERT` … `COMMIT`). A lost version race
  or failed assertion rolls both back and surfaces as `409`. BigQuery transactions still provide no
  distributed locking; the run lease serializes competing writers.
- Approval is implemented; purchase-order creation and the supplier outbox are implemented in
  ADRs 0014/0015. Receiving and invoice matching remain future workflows.
