# ADR 0011 — Dead-letter replay and incremental on-hand projection

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0010 (inventory ledger and idempotent sink)

## Context

The Pub/Sub dead-letter topic is transport, not an operator interface: it is not queryable, does not
record replay state, and is awkward to inspect from an incident. The on-hand projection was also a
view over the full movement history, so every read or reconciliation could scan all history.

## Decisions

- **Durable dead-letter records.** `DeadLetterStore` persists the structured envelope and replay
  state. Local/test uses a bounded in-memory adapter; production uses
  `retail_event_dead_letters` in BigQuery. The Pub/Sub topic remains the transport.
- **Operator replay.** `GET /api/v1/retail/events/dead-letters` lists records;
  `POST /api/v1/retail/events/dead-letters/{eventId}/replay` republishes the original event and
  marks the record `REPLAYED`. Replay is safe to retry because the ledger sink dedupes by
  `event_id`.
- **Replay is explicit.** No automatic replay loop. An operator fixes the cause, replays, and
  watches metrics. Keep the endpoint behind `retail.admin`.
- **Incremental projection table.** `inventory_on_hand_projection` is a partitioned/clustered table.
  `inventory_on_hand_daily` is a stable view over it. Read SQL does not change when the refresh
  strategy changes.
- **Lookback refresh.** Incremental refresh recomputes a configurable lookback window so late
  events are corrected. Full refresh merges all movement dates. The scheduler is opt-in
  (`RETAIL_PROJECTION_ENABLED=true`) because it writes to BigQuery.
- **Writer identity.** Projection and replay writes run under the worker/admin identity, not the
  public read-only API identity.

## Consequences

- Operators can inspect and replay failures without a custom script.
- The projection has a freshness SLO; it is not updated synchronously on the event path.
- Full refresh MERGEs and deletes projection rows absent from the ledger
  (`WHEN NOT MATCHED BY SOURCE THEN DELETE`); incremental refresh only merges the affected window.
- At high volume, run the projection as a Cloud Run Job/Workflow and consider micro-batch staging.
- A DLQ record may exist without a Pub/Sub message (or vice versa); the BigQuery record is the
  source for replay, and the topic is the transport.
