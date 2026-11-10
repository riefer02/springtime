# Operations: SLOs, failure modes, and runbooks

This is the operational contract for the service. It states what "healthy" means, how the system
behaves when each dependency fails, and what an operator does next. Keep it in sync with the code;
`ops/prometheus/springtime-alerts.yml` implements the alert thresholds named here.

## Critical journeys

| Journey | Entry point | What must stay true |
| --- | --- | --- |
| Analytical query | `POST /api/v1/query` | Read-only, byte-bounded, returns a job id and truncation flag |
| Store read | `GET /api/v1/retail/**` | Bounded page/byte budget, `404` for unknown stores, no silent truncation |
| Event ingestion | `POST /api/v1/retail/events` | `202` only after the broker accepts the event; `eventId` is the idempotency key |
| Ledger write | consumer → `inventory_movements` | Ack after commit; duplicates produce one row; retryable outages nack |
| Replenishment decision | `POST /replenishment-runs`, proposal decisions | Lease-serialized run, optimistic concurrency, append-only audit |
| Purchase order | `POST /purchase-orders`, submit/cancel | Postgres transaction; outbox enqueue is atomic with the status change |
| Supplier transmission | outbox worker, retry/ack endpoints | Claimed with `FOR UPDATE SKIP LOCKED`; bounded retries; terminal `FAILED` is visible |

## Service-level objectives

Targets assume one region and the default configuration. Measure over 28 days unless noted.

| SLI | Definition | SLO | Error budget |
| --- | --- | --- | --- |
| API availability | Non-5xx responses ÷ all responses on `/api/v1/**` | 99.9% | 43m 12s / 28d |
| Query latency | `http_server_requests` p95 for `POST /api/v1/query` | < 3s (excluding BigQuery queueing) | — |
| Retail read latency | p95 for `GET /api/v1/retail/**` | < 2s | — |
| Event acceptance | p95 for `POST /api/v1/retail/events` | < 250ms | — |
| Ledger visibility | Event accepted → visible in `inventory_movements` | p95 < 5s, p99 < 30s | — |
| Projection freshness | `time() - retail.inventory.projection.last_success_timestamp_seconds` | < 30m (2× the 15m scheduler) | — |
| Purchase-order integrity | Submitted POs with exactly one lifecycle event and one outbox row | 100% (correctness, not availability) | — |
| Supplier delivery | Transmissions reaching `SENT` within 15m of submit | 99% | — |

**Error-budget policy.** At 50% budget burn, freeze non-essential change and investigate. At 100%,
only reliability fixes ship until the window recovers. The budget is a decision tool, not a target.

## Failure modes

| Failure | Expected behavior | Client sees | Operator action |
| --- | --- | --- | --- |
| BigQuery transient (5xx/timeout/quota) | Read slot times out or job fails; event sink retries, then nacks | `503 UPSTREAM_UNAVAILABLE` / `503 SERVICE_UNAVAILABLE`; retry GETs; POSTs need judgement | Check BigQuery status, quotas, and `jobId`; scale slots only after quota review |
| BigQuery dataset/table missing | `RetailSqlExecutor` raises `DatasetNotProvisionedException` | `503 DATASET_NOT_PROVISIONED`, `retryable=false` | Run `make retail-schema` (and `make retail-seed` for demo); do not retry the request |
| BigQuery slow operator query | Holds one of 8 read slots up to 60s; retail has its own pool | Retail stays available; `/query` may get `429 RATE_LIMITED` | Inspect `jobId`, reduce `maxResults`, raise a quota ticket |
| Postgres unavailable | Repository throws `DataAccessException` | `503 SERVICE_UNAVAILABLE`, retryable | Fail over Postgres; check Hikari pool and `DATABASE_URL` |
| Postgres constraint violation | Deterministic conflict | `409 CONFLICT`, `retryable=false` | Treat as a client/data issue; inspect the unique key |
| Broker full / Pub/Sub publish failure | Intake rejects before `202`; no silent drop | `503 SERVICE_UNAVAILABLE`, retryable | Drain the consumer, check Pub/Sub quotas, replay with the same `eventId` |
| Ledger write repeatedly fails (retryable) | In-process retries, then nack; Pub/Sub redelivers within retention | Event stays pending, not lost | Fix the sink; watch retry-exhaustion and sink-failure alerts |
| Ledger write permanently fails | Dead-letter envelope written and acked | `GET dead-letters` shows the record | Inspect `reason`, fix the payload/schema, replay (admin) |
| Dead-letter publish fails | Processor nacks the original event | Event stays pending | Fix the DLQ topic; redelivery retries the publish |
| Batch queue overload | Fail-closed at count or byte budget | `LEDGER_OVERLOAD` (`retryable=true`) → nack | Scale consumers or raise `RETAIL_LEDGER_BATCH_MAX_PENDING_BYTES` after checking heap |
| Projection refresh fails / lease held | Run marked failed or `409` while held | `409 CONFLICT` for the lease | Inspect `inventory_projection.last_success`; retry after TTL; check BigQuery jobs |
| Supplier adapter failure | Transient: bounded backoff; permanent: `FAILED` | `409` only for invalid state; `FAILED` visible on the transmission | Inspect `lastError` (sanitized); retry after fixing the adapter |
| OIDC issuer/JWKS unavailable | Token validation fails closed | `401 AUTHENTICATION_REQUIRED` | Check issuer reachability and clock skew |
| ADC expiry / IAM change | Dry-run health probe fails; readiness goes DOWN | `503` from readiness gate | Refresh credentials or fix IAM; keep liveness green |

## Alerts

`ops/prometheus/springtime-alerts.yml` is the source of truth. Summary:

| Alert | Expression (conceptually) | Severity | First runbook step |
| --- | --- | --- | --- |
| `SpringtimeDeadLetters` | Any new dead letter in 10m | warning | Read the dead-letter reason; replay after fix |
| `SpringtimeRetryExhaustion` | >5 exhausted retries in 5m | warning | Check BigQuery/ledger health and sink alerts |
| `SpringtimeSinkFailures` | Retryable sink failures sustained 10m | critical | Inspect BigQuery error codes; check slot saturation |
| `SpringtimeBatchQueueSaturation` | Pending > 1500 for 5m | warning | Check consumer throughput and heap; scale out |
| `SpringtimeBatchRejections` | Any `LEDGER_OVERLOAD` in 5m | critical | Reduce intake or scale consumers immediately |
| `SpringtimeBatchFallbackStorm` | >10 per-row fallbacks in 5m | warning | Find the poison row in logs; fix the payload |
| `SpringtimeProjectionStale` | Last success > 30m ago | warning | Check the projection job, lease, and BigQuery jobs |
| `SpringtimeProjectionNeverSucceeded` | Gauge still 0 for 30m | warning | Confirm the scheduler is enabled and the job runs |
| `SpringtimeReconciliationDrift` | Any drift pair in 1h | critical | Inspect the drift report, then correct with a reversing movement |
| `SpringtimeSupplierFailures` | Any failed transmission in 15m | warning | Inspect `lastError`; fix and retry explicitly |
| `SpringtimeReplenishmentRunFailures` | Any `FAILED` run in 15m | warning | Read `errorMessage`; check BigQuery jobs and the lease |
| `SpringtimeHttp5xxRatio` | 5xx ÷ all > 1% for 10m | critical | Use `requestId` and `traceId` to find the failing dependency |

## Capacity and tuning

Defaults are per instance. Tune only with load evidence; every limit clamps down, never up.

| Resource | Default | Consequence of exhaustion |
| --- | --- | --- |
| `BIGQUERY_MAX_CONCURRENT_QUERIES` | 8 | `/query` waits 2s, then `429` |
| Retail read pool | `max(1, read slots / 2)` = 4 | Retail reads wait 2s, then `429` |
| `BIGQUERY_WRITER_MAX_CONCURRENT_QUERIES` | 4 | Controlled DML waits, then `429` |
| Hikari pool | 10 | 5s connection wait, then `503` |
| Ledger batch | 100 rows / 25ms linger / 2000 pending / 32 MiB | `LEDGER_OVERLOAD`, nack |
| Ledger caller wait | 75s | `LEDGER_TIMEOUT`, nack |
| In-process retries | 5 attempts, 200ms ×2, cap 10s | nack → broker retry |
| Log queue | 1024, blocking | Requests backpressure at log-sink throughput |
| Scheduler pool | 4 | Relay/outbox ticks queue behind long jobs |

**Cost controls.** Every BigQuery job carries `surface`, `request_id`, `trace_id`, and `span_id`
labels, so billing exports can be split by surface. Per-query ceilings:
`BIGQUERY_MAX_BYTES_BILLED` (1 GiB), `RETAIL_MAX_BYTES_BILLED` (256 MiB),
`BIGQUERY_WRITER_MAX_BYTES_BILLED` (1 GiB). Response size is capped by `MAX_RESPONSE_BYTES`.
Retail SQL filters the partition date; keep it that way when adding queries.

## Local observability stack

```bash
make obs-up       # API + Postgres + metrics proxy + Prometheus + Grafana + OTel collector
make obs-down
make obs-config   # promtool, dashboard JSON, compose-overlay validation
```

| Component | URL | Notes |
| --- | --- | --- |
| API | `http://localhost:8080` | `API_PORT` |
| Prometheus | `http://localhost:9090` | `PROMETHEUS_PORT`; rules load from `ops/prometheus` |
| Grafana | `http://localhost:3000` | `GRAFANA_PORT`; admin password `GRAFANA_ADMIN_PASSWORD` |
| Tempo | `http://localhost:3200` | `TEMPO_PORT`; trace storage |
| Loki | `http://localhost:3100` | `LOKI_PORT`; logs shipped by Grafana Alloy |
| Alertmanager | `http://localhost:9093` | `ALERTMANAGER_PORT`; agnostic routing |
| OTLP/HTTP | `http://localhost:4318` | `OTEL_HTTP_PORT`; point `OTEL_EXPORTER_OTLP_ENDPOINT` here |
| OTLP/gRPC | `localhost:4317` | `OTEL_GRPC_PORT` |

The **metrics proxy** exists because `/actuator/**` is authenticated: nginx injects `X-API-Key` so
Prometheus never stores a credential. Scrape target: `metrics-proxy:9100`.

**Telemetry pipeline (open source):**

```
app JSON logs ──► Grafana Alloy ──► Loki ──┐
app OTLP spans ─► OTel collector ─► Tempo ─┼─► Grafana (trace ↔ log ↔ metric links)
app metrics ────► nginx proxy ────► Prometheus ─┘
                                       └──► Alertmanager (routing)
```

- Grafana provisions Prometheus, Tempo, and Loki datasources; a log line's `traceId` links to the
  trace, and a span links back to its logs.
- The OTel collector exports to Tempo by default; replace `exporters.otlphttp/tempo` with any OTLP
  backend (Grafana Cloud, Honeycomb, Datadog, Jaeger) and keep the pipeline.
- **Alert routing is deliberately agnostic.** `ops/observability/alertmanager/alertmanager.yml` uses
  a no-op default receiver so rules can be evaluated and inspected without an external dependency.
  Add a `webhook_configs` (or Slack/email/PagerDuty) receiver and point `route.receiver` at it.
- Dashboard: [springtime-overview.json](../../ops/observability/grafana/dashboards/springtime-overview.json),
  provisioned into the `Springtime` folder.

**Readiness note:** readiness includes the BigQuery dry run, so a local stack without credentials is
*correctly* not ready while liveness stays green. That is why the chaos checks assert liveness, a
non-200 readiness, and mapped control-plane reads rather than assuming readiness can recover without
ADC.

## Hosted demo

The Cloud Run + Cloud SQL + BigQuery deployment path, monthly cost model (~$10–15 running, ~$2
paused), and guardrails are in [docs/deployment/gcp.md](../deployment/gcp.md). The local LGTM stack
is for development; point `OTEL_EXPORTER_OTLP_ENDPOINT` at Grafana Cloud or your own collector for a
hosted demo.

## Load testing

```bash
make loadtest                                   # smoke: liveness + authenticated list
make loadtest SCRIPT=/scripts/retail-read.js    # requires a seeded dataset
make loadtest SCRIPT=/scripts/event-intake.js   # intake only unless the consumer is enabled
make loadtest SCRIPT=/scripts/mixed.js
```

Scenarios and thresholds live in [ops/loadtest](../../ops/loadtest/README.md). Thresholds encode the
SLOs above: a breached threshold fails the run. Without BigQuery credentials, retail scenarios fail
with `503 DATA_NOT_READY`; that is the expected signal, not a harness problem.

## Failure injection

```bash
make chaos
```

Scenarios in [ops/chaos](../../ops/chaos/README.md): Postgres outage (503 retryable, recovery),
broker saturation (fail closed, no silent drop), and an unreachable BigQuery project (mapped 502/503,
never `INTERNAL_ERROR`). Each restores the API. They are safe only against the local Compose stack.

## Boot diagnostics

Every start emits one `StartupReporter` JSON line with profile, port, version, project, datasets,
redacted datasource URL, Flyway, broker/consumer, supplier gateway, projection, tracing/OTLP, docs,
and auth mode. It then logs one warning per degraded choice:

| Warning | Meaning / fix |
| --- | --- |
| in-memory broker is not durable | Set `MESSAGING_PUBSUB_ENABLED=true` and configure Pub/Sub for shared deployments |
| OTLP endpoint points at localhost | Start `make obs-up` or set `OTEL_EXPORTER_OTLP_ENDPOINT` to a collector |
| single API key grants operator authority | Set `ADMIN_API_KEY` to split read-only and operator keys |
| Flyway runs during startup | Expected only for local/demo; production applies migrations as a job |
| springdoc is enabled | `/v3/api-docs` is public while enabled; disable in shared environments |
| outbox/worker disabled | Transmissions will not be enqueued/sent |

Readiness (not the boot summary) is the live dependency check: it includes Postgres and the BigQuery
dry run, so a local stack without ADC is correctly not ready while liveness stays green.

## Runbooks

### Dead-letter growth
1. `GET /api/v1/retail/events/dead-letters?limit=50` — note `reason`, `errorType`, `eventType`.
2. Correlate with `requestId`/`traceId` in logs.
3. Fix the payload, schema, or downstream data.
4. Replay one event (admin): `POST .../dead-letters/{eventId}/replay`; replay is idempotent.
5. Watch `retail.events.dead.lettered` return to zero before closing.

### Reconciliation drift
1. `GET /api/v1/retail/inventory/reconciliation?storeId=...&tolerance=0`.
2. Confirm the drift is real (late event, manual load, projection lag).
3. Correct with a reversing movement carrying a new `eventId`; never update a balance in place.
4. Refresh the projection; re-run reconciliation.

### Stale projection
1. Check `retail.inventory.projection.last_success_timestamp_seconds`.
2. Confirm `RETAIL_PROJECTION_ENABLED` on the worker and that the scheduler runs.
3. Trigger `POST /api/v1/retail/inventory/projection?mode=INCREMENTAL`; use `FULL` only when rows are
   missing or the ledger was rewritten.
4. A `409` means another instance holds the lease; wait for the TTL before retrying.

### Supplier transmissions stuck or failed
1. `GET /api/v1/retail/supplier-transmissions?status=FAILED`.
2. Read sanitized `lastError`; fix the adapter or reference.
3. `POST /{id}/retry` (admin). `ACK` only after the supplier confirms.
4. If rows are `SENDING` after a crash, the worker reclaims them after the configured window.

### BigQuery quota or slots
1. Identify the surface via job labels (`surface=retail|rest-api|inventory-ledger|...`).
2. Lower `maxResults`/page size for the heavy caller; do not raise byte ceilings ad hoc.
3. If retail is starved, raise read slots only after confirming project quota headroom.

## Change safety

- `make verify` is the gate: unit, MVC, security, contract, architecture, resilience, and SQL drift.
- API changes: `make openapi`, review the JSON diff, commit it.
- Postgres: forward-only Flyway migrations; apply as a separate deploy job.
- BigQuery DDL: `make retail-schema`, idempotent, applied by an operator/job, never on startup.
- Deploy the worker and API separately, with separate identities.
- Roll back the application image first; migrations are forward-only by design.

## Known limits

- Live BigQuery is validated only by the opt-in `BigQueryIntegrationTest`; run it against a
  disposable project after schema/seed changes.
- Offset pagination re-scans deep pages; keyset pagination is the planned follow-up.
- The local broker and dead-letter store are non-durable and demo-only.
- The local supplier gateway is not a real EDI/API integration; receiving and invoice matching are not
  implemented.
- Replenishment runs are single-store, bounded to 5,000 proposals, and serialized by a Postgres
  lease that is not renewed mid-run.
- There is no per-key rate limit or query-submission idempotency key yet.
