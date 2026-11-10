# ADR 0007 — Asynchronous events and long-running work

- **Status:** Accepted (implemented by ADR 0009, ADR 0010, and ADR 0015)
- **Date:** 2026-11-09

## Context

The current service is a synchronous query/catalog API. It does not ingest e-commerce events, and
BigQuery is not an event bus, queue, OLTP database, or search engine. Putting event processing or
long transformations on the request path would couple checkout/UX latency to warehouse availability
and cost.

We need a durable place for events to wait when a consumer or BigQuery is unavailable, retries with
bounded behavior, and a way to run long workers without holding HTTP requests.

## Decisions

### Keep the hot path hot

- The query/catalog API stays synchronous and read-oriented.
- Event ingestion, if needed, is a **separate endpoint or, preferably, the source publishes directly
  to Pub/Sub**. The intake path validates minimally and returns `202 Accepted`; it does not write to
  BigQuery inline.
- Never run a BigQuery DDL/DML/load job on the checkout request path.

### Durable ingress: Pub/Sub

- Use **Pub/Sub** as the durable event buffer. It retains undelivered messages for a configurable
  retention window (default 7 days; currently up to 31 days for subscriptions — verify tier), retries
  with backoff, and supports dead-letter topics.
- Consumers acknowledge only after the work is durably accepted by the sink (for BigQuery, after the
  write/batch commit succeeds).
- Use a **dead-letter topic** for poison messages, plus a replay procedure.
- Use ordering keys only where per-entity ordering is genuinely required; do not make the whole topic
  ordered.

### Workers

| Need | Google Cloud service |
| --- | --- |
| Low-latency event handling / enrichment | Cloud Run service subscribed to Pub/Sub |
| Long batch/backfill, heavy transforms | **Cloud Run Jobs** (task timeout up to 168 hours) |
| Multi-step orchestration with retries | Workflows |
| Targeted HTTP task with per-queue rate limits/retries | Cloud Tasks |
| Continuous streaming/ETL, windowing | Dataflow |
| Cron/schedules | Cloud Scheduler |
| React to GCP resource events | Eventarc |

Start with **Pub/Sub + one Cloud Run service consumer + one Cloud Run Job for backfills**. Add
Dataflow/Workflows only when volume, ordering, or multi-step orchestration justifies them.

### BigQuery sink

- Prefer the **Storage Write API** for high-throughput event writes, or **batch loads** for backfills.
- Do **not** submit one `INSERT` query job per event; query-job overhead and quotas are the wrong
  tool for event firehose traffic.
- Model an append-only `events_raw` table with `event_id`, `event_type`, `occurred_at`,
  `ingested_at`, `schema_version`, `source`, and a JSON payload. Partition by `DATE(occurred_at)`,
  cluster by `event_type`/`customer_id`.
- Assume **at-least-once** delivery. Deduplicate with `event_id` using a `MERGE` or a deduped view;
  Storage Write API exactly-once offsets are possible but more complex.
- Keep the query API read-only. Event writes belong to the worker identity, not the runtime API.

### Long-running processes

- Use Cloud Run Jobs for work measured in minutes/hours; do not hold a Cloud Run service request open.
- Make workers **idempotent and checkpointed**. A retry must not double-apply a financial event.
- Use Workflows when a process has multiple dependent steps, compensation, or human approval.
- Use Dataflow when the transform is continuous, windowed, and high-volume.
- Never run migrations or long backfills on application startup.

### BigQuery is not Elasticsearch

- BigQuery is a columnar **OLAP warehouse**: serverless, separated storage/compute, SQL, huge scans
  and aggregations. It is not designed for single-row OLTP or sub-second point lookups.
- Elasticsearch is a **search engine**: inverted indexes, document-oriented, near-real-time full-text
  search. Use it only if you need full-text search/ranking or low-latency document retrieval.
- BigQuery has search indexes and `SEARCH`, but that does not make it a general Elasticsearch
  replacement. Use BigQuery for analytics and event history; add a search store only for search.

## Consequences

- The API stays responsive if BigQuery or a worker is down; Pub/Sub buffers events within retention.
- There is a new operational surface: topics, subscriptions, DLQ, worker deployment, IAM, and
  idempotency. Add it only when an event feature exists.
- Event schema and data contracts must be versioned like the API contract.
- At-least-once delivery is the default; exactly-once claims require specific BigQuery write
  semantics and tests.

## Next implementation slice (when an event feature exists)

1. Define the event JSON schema and `schema_version`.
2. Add a `POST /api/v1/events` intake (or source-direct Pub/Sub publish) returning `202` + `eventId`.
3. Create the topic, subscription, DLQ, and a dedicated worker service account.
4. Add one Cloud Run worker subscribed to the topic; write via Storage Write API/batch; ack after
   commit.
5. Add an `events_raw` BigQuery table, partition/cluster policy, dedupe view, and metrics.
6. Add a Cloud Run Job for backfills/replays; schedule with Cloud Scheduler.
7. Add Workflows only for multi-step flows; Dataflow only for continuous high-volume transforms.
