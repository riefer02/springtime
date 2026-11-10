# ADR 0012 — Replenishment runs and proposals

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0008 (retail copilot), ADR 0010 (ledger), ADR 0011 (projection/replay)

## Context

The retail API could compute a live replenishment worklist, but a live query is not a decision
record. It cannot be reviewed later, compared across days, approved, or turned into an order. It also
recomputes the same expensive decision on every refresh.

## Decisions

- **A run is the durable decision record.** `replenishment_runs` stores the store, business date,
  parameters, requester, status, timestamps, proposal count, total order value, and a safe error
  class.
- **Proposals are the output.** `replenishment_proposals` stores the inputs and the decision:
  available units, average daily units, days of supply, lead time, effective reorder point,
  recommended units, order value, and a reason.
- **`run_id` is the idempotency key.** A client may supply it; otherwise the server generates a
  UUID. Re-running the same `run_id` clears and replaces that run's proposals **only while every
  proposal is still `PROPOSED`**; once any decision exists the re-run is `409` and needs a new
  `run_id`. A new `run_id` creates a new decision record.
- **Bounded single-store run, in-process.** The current run is synchronous and capped at one store
  and 5,000 proposals so it is safe on a request path. Multi-store/backfill work should move to a
  Cloud Run Job or Workflow.
- **Proposal decisions are split out.** ADR 0013 defines the `PROPOSED`/`APPROVED`/`REJECTED`/
  `ORDERED` state machine, optimistic concurrency, and audit trail. Purchase-order creation and
  supplier transmission remain deferred.
- **Read side is separate.** `GET /replenishment-runs`, `GET /replenishment-runs/{runId}`, and
  `GET /replenishment-runs/{runId}/proposals` read the persisted decision; the legacy live
  `/replenishment` endpoint remains a quick worklist.

## Consequences

> **Amendment:** purchase-order creation is implemented by ADR 0014, and durable supplier
transmission by ADR 0015. Concurrent creation for one `runId` is serialized by a Postgres run lease
(`control_run_leases`, ADR 0015 control plane); the BigQuery writes remain a multi-job sequence.

- Decisions are reproducible, reviewable, and idempotent.
- A failed run is marked `FAILED`; a retry with the same `run_id` replaces it.
- The run writes several DML statements and is not transactionally atomic. If `complete` fails after
  the proposal merge, the run remains `RUNNING`; retrying the same `run_id` clears and rebuilds it.
- The 5,000-proposal cap and single-store scope are deliberate guardrails, not the final scale
  design.
- Approval/rejection is implemented with an append-only decision audit (ADR 0013); purchase-order
  creation and supplier transmission are the next workflow.
