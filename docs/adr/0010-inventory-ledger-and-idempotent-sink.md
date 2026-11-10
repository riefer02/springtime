# ADR 0010 — Inventory movement ledger and idempotent event sink

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The retail v1 slice treated `inventory_snapshots` as the source of truth. That is enough for a
dashboard, but it cannot answer “why is on-hand what it is?”, cannot replay an event safely, and
cannot reconcile a POS/receiving feed with a physical count. Event intake also returned `202` before
there was a durable write path.

## Decision

- **Inventory is an append-only ledger.** `inventory_movements` is the source of truth. On-hand is a
  projection, never a mutable balance. Corrections are reversing movements.
- **The API publishes; the consumer writes.** `POST /api/v1/retail/events` validates and publishes an
  `EventEnvelope`. `RetailEventProcessor` translates it into a signed `InventoryMovement` and calls
  `InventoryMovementSink`.
- **Idempotency is enforced at the sink.** `BigQueryInventoryMovementSink` executes one `MERGE` on
  `(event_id, business_date)` (the business date is derived from `occurredAt` in the configured
  business zone). A duplicate event returns zero affected rows and is acknowledged as `DUPLICATE`;
  in batch mode the outcome is batch-level, and reads dedupe by `event_id`.
- **Ack after commit.** Pub/Sub acks after the ledger write commits or after a structured dead-letter
  publish succeeds. Transient failures nack; permanent failures go straight to the DLQ.
- **The DLQ carries context.** `DeadLetterEnvelope` includes the original envelope, attempts, reason,
  error type, original topic, and failure time so a replay tool can be built without guessing.
- **Read models are derived.** `inventory_movements_deduped` and `inventory_on_hand_daily` are views
  for reads and demos. At scale, the on-hand projection becomes an incremental scheduled table.
- **Reconciliation is first-class.** `GET /api/v1/retail/inventory/reconciliation` compares the
  ledger projection with the snapshot and reports drift; it does not auto-correct.

## Consequences

- Duplicate and late deliveries are safe; at-least-once is honest.
- The ledger is auditable, replayable, and testable with a fake sink.
- The request path stays fast and does not depend on BigQuery write latency.
- The local in-memory broker is a demo adapter only; it fails closed and is not durable.
- The seed creates a demo ledger from snapshot deltas so reconciliation has a baseline. That bridge
  is not how production movements should be produced.
- At high volume, replace the single-row `MERGE` with micro-batch staging and a partition-scoped
  merge (or an append table plus a deduped view), and maintain `inventory_on_hand_daily` as an
  incremental scheduled table rather than a full-history view.
- The next correctness step is a replenishment run/proposal model; DLQ replay and an incremental
  on-hand projection are defined in ADR 0011.
