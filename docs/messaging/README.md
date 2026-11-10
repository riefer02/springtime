# Messaging, retries, and dead letters

Retail event intake is asynchronous: the API accepts an event, publishes it to a broker, and returns
`202`. Processing is not on the request path.

## Current implementation

- `POST /api/v1/retail/events` validates the request (identifier format, event window, quantity,
  payload currency/cost) and publishes an `EventEnvelope` to `MESSAGING_EVENTS_TOPIC`.
- `MessagePublisher` has two adapters:
  - `InMemoryMessagePublisher` — default local/test broker. It is bounded, fails closed when full,
    stores dead letters separately, and is not durable.
  - `PubSubMessagePublisher` — enabled with `MESSAGING_PUBSUB_ENABLED=true`; flushes on shutdown.
- `PubSubRetailEventSubscriber` is the production consumer. It deserializes the envelope, calls the
  processor, acks on `PROCESSED`/`DUPLICATE`/`DEAD_LETTERED`, and nacks transient failures. A
  retryable ledger outage that outlives the in-process retry budget is rethrown as transient and
  nacked, so Pub/Sub's own retry policy (and retention) applies before anything is dead-lettered.
- `LocalRetailEventRelay` is the local-only consumer for the in-memory broker. It drains a bounded
  batch, requeues transient failures, and is disabled with `MESSAGING_LOCAL_CONSUMER_ENABLED=false`.
- `RetailEventTranslator` validates the envelope and maps it to a signed `InventoryMovement`.
- `RetailEventProcessor` retries only transient ledger failures with exponential backoff + jitter.
  Permanent validation/schema failures go straight to the DLQ.
- `BigQueryInventoryMovementSink` writes one `MERGE` keyed by `(event_id, business_date)`; a batch
  with zero inserted rows means every event in it is a duplicate and is safe to acknowledge. With
  batching enabled, `BufferingInventoryMovementSink` coalesces concurrent writes; the outcome is
  batch-level (any insert → `INSERTED` for every caller), a retryable failure fails the whole batch,
  and deterministic row failures use a bounded per-row fallback.
- `DeadLetterPublisher` writes a structured `DeadLetterEnvelope` with `originalTopic`, `attempts`,
  `reason`, `errorType`, and `failedAt` so replay is possible.
- `DeadLetterStore` persists the envelope and replay state: in-memory locally, BigQuery in
  production. `GET /api/v1/retail/events/dead-letters` lists them;
  `POST /api/v1/retail/events/dead-letters/{eventId}/replay` republishes the original event.
- `RetryExecutor` is reusable; `RetryProperties` controls attempts, initial delay, multiplier, max
  delay, and jitter. The batch sink's retained-payload budget is
  `RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES`; exceeding it fails closed with `LEDGER_OVERLOAD`.
- **Trace context travels with the message.** Publishing injects a W3C `traceparent` (and
  `tracestate` when present) into the broker attributes; both consumers start a
  `retail-event.consume` span with that context as the remote parent. The local broker keeps the
  attributes on `PublishedMessage`; Pub/Sub stores them as message attributes. Ack/nack behavior is
  unchanged, and with tracing disabled the propagation degrades to a no-op.

The Pub/Sub topic/subscription/DLQ policy is infrastructure. Do not claim exactly-once delivery:
at-least-once is the contract, and dedupe is enforced by the ledger `MERGE`.

## Event contract

```json
{
  "eventId": "evt-20261109-0001",
  "eventType": "SALE",
  "occurredAt": "2026-11-09T12:00:00Z",
  "storeId": "ST-001",
  "productId": "SKU-0001",
  "quantity": 2,
  "payload": {"channel": "IN_STORE"}
}
```

- `eventId` is required and is the **idempotency key**. Retries must reuse it.
- `eventType`: `SALE`, `RETURN`, `INVENTORY_ADJUSTMENT`, `SHIPMENT`, `OPENING_BALANCE`.
- `quantity` is positive for `SALE`, `RETURN`, `SHIPMENT`, and `OPENING_BALANCE`;
  `INVENTORY_ADJUSTMENT` is signed.
- `occurredAt` must be within the configured clock skew/future window and maximum age.
- `schemaVersion` is fixed to `1` in the envelope.
- `payload` is bounded to 50 keys and should not contain secrets/PII. Optional `unitCost`, `currency`,
  and `sourceDocumentId` are validated at intake.

## Pub/Sub setup (production)

```bash
PROJECT_ID=your-project
gcloud pubsub topics create retail-events
gcloud pubsub topics create retail-events-dlq

gcloud pubsub subscriptions create retail-events-worker \
  --topic=retail-events \
  --ack-deadline=60 \
  --dead-letter-topic=retail-events-dlq \
  --max-delivery-attempts=5 \
  --min-retry-delay=10s \
  --max-retry-delay=600s
```

Then:

```bash
MESSAGING_PUBSUB_ENABLED=true
MESSAGING_EVENTS_TOPIC=retail-events
MESSAGING_DLQ_TOPIC=retail-events-dlq
MESSAGING_SUBSCRIPTION=retail-events-worker
MESSAGING_CONSUMER_ENABLED=true
```

The consumer starts with the application. If the subscription does not exist or credentials are
missing, startup fails loudly rather than accepting events into a topic nobody reads.

IAM:

- API publisher: `roles/pubsub.publisher` on `retail-events`; BigQuery `jobUser` + dataset-scoped
  `dataViewer`. Set `MESSAGING_CONSUMER_ENABLED=false` on the public API deployment.
- Worker: `roles/pubsub.subscriber` on the subscription; `roles/pubsub.publisher` on the DLQ;
  BigQuery `jobUser` + dataset-scoped `dataEditor` on the retail dataset. Set
  `MESSAGING_CONSUMER_ENABLED=true`.
- BigQuery sink: run it under the worker's writer identity, never the read-only API identity.

## Processing rules

1. **Acknowledge only after the sink commits.** A crash after ack loses the event.
2. **Deduplicate by `eventId` at the sink.** At-least-once means duplicates are normal. Use a
   BigQuery `MERGE` keyed by `(event_id, business_date)` and keep the deduped view tie-break
   `(ingested_at, event_time)`.
3. **Retry transient failures** (BigQuery 5xx/timeouts, network) with exponential backoff + jitter.
4. **Do not retry permanent failures** (invalid schema, unknown store/SKU, bad payload) forever;
   route to DLQ.
5. **DLQ is not a graveyard.** Alert on DLQ depth, inspect with
   `GET /api/v1/retail/events/dead-letters`, and replay after fixing the cause with
   `POST /api/v1/retail/events/dead-letters/{eventId}/replay`. Keep it admin-only.
6. **Ordering** is opt-in per entity. Use a Pub/Sub ordering key (e.g. `storeId`) only where
   per-store order matters; do not make the whole topic ordered.
7. **Backpressure**: keep concurrency bounded; Cloud Run scales instances, but BigQuery quotas and
   the sink's write rate are the real limits.
8. **Always set event time** (`occurredAt`) and event id; do not use processing time as the event
   time.

## Metrics

- `retail.events.processed{event_type,result}` — inserted vs duplicate.
- `retail.events.retry.exhausted{event_type}` — retry budget exhausted.
- `retail.events.sink.failures{event_type,retryable}` — ledger write failures.
- `retail.events.dead.lettered{event_type,reason}` — DLQ writes.
- `retail.inventory.movement.write{movement_type}` — sink latency.
- `retail.inventory.reconciliation{status}` and `retail.inventory.reconciliation.drift.pairs`.
- `retail.inventory.movements.read` — ledger rows returned.
- `retail.inventory.projection.last.success.timestamp.seconds` — projection freshness gauge, updated
  on every successful refresh.
- `retail.inventory.movement.batch.pending` — batch-sink queue depth; alert before it saturates.
- `retail.inventory.movement.batch.rejected` / `.fallback` — fail-closed overload and per-row
  fallback.

Alert on DLQ growth, retry exhaustion, sink failures, and reconciliation drift. Do not tag by
`eventId` or `storeId`; that would create unbounded cardinality.

## Replay

- `GET /api/v1/retail/events/dead-letters?limit=50` lists durable dead letters with `eventId`,
  `reason`, `attempts`, `failedAt`, and `replayStatus`.
- `POST /api/v1/retail/events/dead-letters/{eventId}/replay` republishes the original
  `EventEnvelope` to the event topic and marks the record `REPLAYED`.
- Replay is safe to retry: the ledger sink dedupes on `(eventId, businessDate)`. If the first attempt
  partially applied, the replay becomes a duplicate acknowledgement; reads stay deduped by `eventId`.
- The local store is in-memory; the production store is `retail_event_dead_letters` in BigQuery.
  Keep DLQ replay behind `retail.admin` and audit the operator action.

## Failure matrix

| Failure | Behavior |
| --- | --- |
| Broker unavailable at publish | API returns `503 SERVICE_UNAVAILABLE`; client retries with same `eventId` |
| Local broker full | Publish fails with `503`; the event is never silently evicted |
| Worker crash before ack | Pub/Sub redelivers; sink dedupes by `eventId` |
| BigQuery temporarily down | Bounded in-process retry, then `nack`; Pub/Sub redelivers within retention before any DLQ |
| Permanent event/schema failure | Straight to DLQ; no retry loop |
| Duplicate delivery | Sink `MERGE` on `(event_id, business_date)`; no double count |
| Broker outage after accept | Pub/Sub retains messages; no request-path impact |
| Long processing | Cloud Run Job/worker, not the HTTP request; checkpoint or batch |

## What is deliberately not built yet

- Automatic replay policies (replay is operator-triggered).
- A Cloud Run worker deployment manifest; the subscriber runs in-process today.
- Query-submission idempotency (the generic `/query` endpoint remains non-idempotent).
- ~~Outbox for a relational source~~ — implemented for supplier transmissions in Postgres
  (Flyway V1–V7); only replenishment run/proposal state remains in BigQuery.
- Per-event ordering keys; the ledger is idempotent but not ordered.
- A per-key rate limit or daily event budget.

These are the next slices; the event contract, broker port, consumer, ledger sink, retry primitive,
and DLQ/replay path are already in place.
