# Events, async processing, and long-running workers

> **Status:** implemented. Event intake, the consumer, durable dead-letter replay, and the Postgres
> supplier outbox are live (ADR 0009, ADR 0010, ADR 0015). The generic `/api/v1/query` path remains
> synchronous; the "what not to build yet" guidance below is historical context, not a statement
> that no async work exists.

The async path exists to keep event ingestion and long-running work off the query hot path. This
document describes the Google Cloud shape and the implemented local fallback.

## The 30-second answer

```text
Checkout / event source
        │  publish (fast, durable)
        ▼
   Pub/Sub topic ──► dead-letter topic
        │
        │ push/pull
        ▼
 Cloud Run service (validate/enrich) ──► BigQuery (Storage Write API / batch)
        │
        └──► Cloud Run Job (backfill/replay)
Cloud Scheduler ──► Cloud Run Job
Workflows ──► multi-step jobs / compensations
Dataflow ──► continuous high-volume streams
```

- **Do not write to BigQuery inline in checkout.** Publish to Pub/Sub and return `202`.
- Pub/Sub holds undelivered messages (default 7 days; configurable) so a worker or BigQuery outage
  does not lose the event or block the user.
- Acknowledge only after the write is durably committed; use a DLQ for poison messages.
- Use Cloud Run Jobs for long work; don’t hold an HTTP request open for minutes/hours.

## Which Google Cloud service?

| Need | Use | Why |
| --- | --- | --- |
| Durable event buffer/fan-out | **Pub/Sub** | Managed, retention, retries, DLQ, push/pull, ordering keys |
| Low-latency event consumer | **Cloud Run service** | Scales with load; Pub/Sub push or pull |
| Long batch/backfill/replay | **Cloud Run Jobs** | Task timeout up to 168 hours; no request held |
| Multi-step process/compensation | **Workflows** | Ordered steps, retries, error handling |
| HTTP task with per-queue rate/retry | **Cloud Tasks** | Targeted delivery, queue-level throttling |
| Continuous/windowed streaming | **Dataflow** | Exactly-once/streaming transforms at volume |
| Cron | **Cloud Scheduler** | Calls a job or endpoint on schedule |
| React to GCP events | **Eventarc** | Routes Cloud Storage/Firestore/etc. events |

Start with Pub/Sub + one Cloud Run consumer + one Cloud Run Job. Add Workflows/Dataflow only when
the flow or volume demands it.

## Failure behavior

| Failure | What happens |
| --- | --- |
| BigQuery unavailable/slow | In-process retry budget, then `nack`; Pub/Sub redelivers with backoff and the event stays within retention |
| Worker crashed | Unacked messages redeliver; consumer must be idempotent |
| Poison event | Non-retryable failure or exhausted retries with a permanent cause: dead-letter and ack; inspect/replay |
| Checkout surge | Pub/Sub absorbs burst; workers scale; checkout does not wait on BigQuery |
| Long backfill | Cloud Run Job, not a request; checkpoint and resume |
| Duplicate delivery | Deduplicate by `event_id`; `MERGE` or a deduped view |

**At-least-once is the default.** Do not claim exactly-once without explicit BigQuery write offsets
and tests. Financial/e-commerce events need an idempotency key from the source.

## BigQuery write pattern

- Prefer **Storage Write API** for high-throughput event ingestion, or **batch load** for backfills.
- Do not create one query job per event; job overhead/quotas are wrong for a firehose.
- Suggested `events_raw`:

```sql
CREATE TABLE `springtime.events_raw` (
  event_id      STRING NOT NULL,
  event_type    STRING NOT NULL,
  schema_version INT64 NOT NULL,
  occurred_at   TIMESTAMP NOT NULL,
  ingested_at   TIMESTAMP NOT NULL,
  source        STRING,
  customer_id   STRING,
  payload       JSON
)
PARTITION BY DATE(occurred_at)
CLUSTER BY event_type, customer_id;
```

- Keep the raw table append-only. Build deduped/typed views on top.
- Version event schemas; store `schema_version` and validate in the worker.
- Partition by event time, not ingestion time, unless late-arriving events dominate.

## Source of truth and contracts

- The event producer owns the event contract; publish a JSON Schema/version alongside it.
- BigQuery is the analytical sink, not the event contract authority.
- The query API remains read-only; event writes use a separate worker identity.
- Migrations/DDL for event tables follow [ADR 0005](../adr/0005-bigquery-schema-migrations.md).

## BigQuery vs Elasticsearch

| | BigQuery | Elasticsearch |
| --- | --- | --- |
| Model | Columnar OLAP warehouse | Document/search engine |
| Best at | Huge scans, aggregations, SQL analytics | Full-text search, ranking, low-latency lookups |
| Single-row OLTP | No | Not a transactional DB either |
| Event queue | No | No |
| Use here | Event history/analytics | Only if you need search/ranking |

BigQuery is not “Elasticsearch as a database.” It is an analytical warehouse; use Pub/Sub for
transport and BigQuery for analysis.

## What not to build yet

- No Kafka, Dataflow, Workflows, or a worker fleet before an event feature exists.
- No BigQuery write path in the query API.
- No per-event `INSERT` query jobs.
- No event ingestion on app startup.
- No generic “async everything” abstraction; add one consumer and one job, then grow.

## Implemented slices

1. Event envelope with `event_id`, `event_type`, and `schema_version`.
2. Pub/Sub topic + subscription + DLQ; bounded in-memory fallback for local runs.
3. `POST /api/v1/retail/events` → `202` + `eventId`.
4. Consumer → idempotent `MERGE` into `inventory_movements` → ack; structured DLQ otherwise.
5. Dead-letter storage and operator-triggered replay, deduped by `event_id`.
6. Postgres supplier outbox for durable transmission with retry and lease recovery.
7. Add Workflows/Dataflow only when volume justifies them.
