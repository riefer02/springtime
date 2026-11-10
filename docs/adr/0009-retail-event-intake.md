# ADR 0009 — Retail event intake, retries, and dead letters

- **Status:** Accepted
- **Date:** 2026-11-09
- **Related:** ADR 0010 (inventory ledger and idempotent sink)

## Context

Retail events (sales, returns, inventory adjustments, shipments) should not be written inline to
BigQuery. The API needs a durable handoff, bounded retry behavior, and an explicit failure path.

## Decisions

- **API publishes, returns `202`.** `POST /api/v1/retail/events` validates and publishes an
  `EventEnvelope`; it does not write to BigQuery.
- **Broker port:** `MessagePublisher` with an in-memory local adapter and a Pub/Sub adapter.
  Pub/Sub is enabled explicitly with `MESSAGING_PUBSUB_ENABLED=true`.
- **Event id is the idempotency key.** `eventId` is required, stable across retries, and the sink
  must deduplicate by it. At-least-once is the delivery model; exactly-once is not claimed.
- **Retry:** `RetryExecutor` with configurable max attempts, exponential backoff, max delay, and
  jitter. Retry only transient failures; permanent failures go to the DLQ immediately.
- **DLQ:** `MESSAGING_DLQ_TOPIC`; Pub/Sub subscription dead-letter policy is infrastructure.
  `RetailEventProcessor` routes permanent failures immediately and exhausted transient failures to
  a structured `DeadLetterEnvelope`.
- **Ack after sink commit.** `PubSubRetailEventSubscriber` acknowledges only after the ledger write
  commits or the dead-letter publish succeeds. The local relay mirrors the same rule.
- **No startup seeding or migration.** Events and retail data are loaded by separate jobs.
- **Infrastructure:** topic/subscription/DLQ/IAM are created by Terraform or documented `gcloud`
  commands, not by the application.
- **Ledger sink:** ADR 0010 defines the append-only `inventory_movements` table and the `MERGE` on
  `event_id`.

## Consequences

- The request path is decoupled from BigQuery availability and write latency.
- Duplicate deliveries are expected; the BigQuery sink uses `MERGE`/dedupe.
- DLQ depth must be monitored; a DLQ is not a success path.
- The in-memory adapter is local-only and bounded; it fails closed and is not durable.
- Query-submission idempotency, a DLQ replay tool, and a Cloud Run worker deployment remain
  follow-ups.
