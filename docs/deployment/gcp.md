# Deploying the demo to GCP

The cheapest credible deployment is **Cloud Run + Cloud SQL Postgres + BigQuery**, with Artifact
Registry, Secret Manager, and Cloud Logging/Monitoring. No GKE, no Dataflow, no Composer, no
Memorystore, no Dataproc.

- **Cloud Run** runs the API, scales to zero, and gives you HTTPS.
- **Cloud SQL** is the OLTP control plane (purchase orders, outbox, leases). It is the only
  always-on cost.
- **BigQuery** is the analytical plane (ledger, projections, reads). At demo volume it stays inside
  the free tier.
- **Artifact Registry / Secret Manager / Cloud Build / Logging / Monitoring** are supporting
  services, effectively free at this scale.

Telemetry stays open source: the local LGTM stack (Prometheus, Tempo, Loki, Grafana, Alertmanager)
is in `compose.observability.yaml`. For the hosted demo, point
`OTEL_EXPORTER_OTLP_ENDPOINT` at Grafana Cloud's free tier or a small self-hosted LGTM VM; the app
exports OTLP/HTTP and does not care which backend answers.

## Easiest path

```bash
PROJECT_ID=your-project ./deploy/gcp/deploy.sh
```

What it does, idempotently:

1. Enables the minimum APIs (Run, SQL, BigQuery, Artifact Registry, Secret Manager, Cloud Build,
   Logging, Monitoring, Trace, IAM).
2. Creates a runtime service account with dataset-level BigQuery access, Cloud SQL client, secret
   access, and log/metric/trace writers.
3. Creates a `db-f1-micro` Cloud SQL Postgres 16 instance (10 GB SSD, backups), database, and user.
4. Creates the BigQuery dataset and runs `make retail-schema retail-seed` (set `SEED_DATA=0` to skip).
5. Stores the database password and API key in Secret Manager.
6. Builds the image with Cloud Build and deploys Cloud Run with scale-to-zero, 2 max instances,
   1 vCPU / 1 GiB, Cloud SQL socket, and Flyway on first start.
7. Prints the URL, secret names, smoke command, and the cost guardrails to set.

Then:

```bash
URL=$(gcloud run services describe springtime-api --region=us-central1 --format='value(status.url)')
KEY=$(gcloud secrets versions access latest --secret=springtime-api-key)
curl -s -H "X-API-Key: $KEY" "$URL/api/v1/retail/stores" | jq
```

To remove everything (including the dataset): `CONFIRM=yes PROJECT_ID=... ./deploy/gcp/teardown.sh`.

### Manual one-liner alternative

For a throwaway deploy where Postgres is already reachable over TLS:

```bash
gcloud run deploy springtime-api --source . --region us-central1 --allow-unauthenticated \
  --set-env-vars GOOGLE_CLOUD_PROJECT=...,DATABASE_URL=jdbc:postgresql://...,DATABASE_USERNAME=...,DATABASE_PASSWORD=...
```

This is faster but does not provision Cloud SQL, secrets, or IAM for you. Prefer the script for a
showcase you intend to keep.

## Cost

List prices, `us-central1`, 730 hours/month. Verify against the current pricing pages; ranges account
for storage and backup rounding. A static demo with no real traffic:

| Service | Configuration | Monthly estimate | Why |
| --- | --- | --- | --- |
| **Cloud Run** | scale-to-zero, 1 vCPU, 1 GiB, max 2 | **$0–1** | 2M requests, 180k vCPU-s, 360k GiB-s free; idle costs nothing |
| **Cloud SQL** | `db-f1-micro`, 10 GB SSD, 3 backups | **$10–13** | ~$8–11 instance + ~$1.70 storage + ~$0.50 backups |
| BigQuery | 10 GB storage, demo queries | **$0** | 10 GB storage and 1 TiB scanned/month free |
| Artifact Registry | ~0.4 GB image | **$0** | 0.5 GB free |
| Secret Manager | 2 secrets, 2 versions | **~$0.12** | first 6 versions free, then per-version |
| Cloud Build | image builds | **$0** | free daily build minutes |
| Logging / Monitoring / Trace | JSON logs, SLO metrics, spans | **$0** | within free tiers at demo volume |
| Egress | API responses | **$0–1** | negligible |
| **Total, running 24/7** | | **≈ $10–15/month** | dominated by Cloud SQL |

- **Pause between demos:** `gcloud sql instances patch springtime-pg --activation-policy=NEVER`.
  Compute billing stops; storage and backups remain, so the bill drops to **~$2/month**. Restart with
  `--activation-policy=ALWAYS` before showing the demo.
- **Warm starts:** `--min-instances=1` removes cold starts but adds roughly **$10–15/month**. Keep 0
  unless the demo is time-critical.
- **Telemetry:** Grafana Cloud's free tier covers a demo's metrics/logs/traces. Self-hosting the LGTM
  stack needs a small VM (~**$13–17/month** for e2-small) and is only worth it if you want the full
  stack in your own project.

### Hard cost guardrails

1. **Budget alert**: billing → budgets → $20/month, alerts at 50% and 100%. The deploy script prints
   the exact `gcloud billing budgets create` command.
2. **BigQuery custom quota**: IAM & Admin → Quotas → "Query usage per day" per project, e.g. 10 GiB.
   This caps a runaway query far below the 1 TiB free tier.
3. **Cloud Run max instances = 2** (already set). This bounds a traffic spike.
4. **Cloud SQL stop/start** around demos; never leave an HA instance running for a static demo.
5. **Log retention**: Cloud Logging defaults to 30 days; lower it if you ship logs elsewhere.

## Local operator notes

This repository keeps deployment documentation agnostic. Real project IDs, keys, database passwords,
logs, and the exact command history belong in a **`.local/`** directory at the repository root:

- `.local/` is gitignored and dockerignored, so it cannot be committed or baked into an image.
- Keep `state.env` (resource IDs), `secrets.env` (keys/passwords, `chmod 600`), `logs/`, `runbook.sh`,
  and `deployment.md` there.
- The committed guide stays reusable; a clone starts from this document and creates its own
  `.local/` as needed.
- Never copy `.local/` values into README, docs, CHANGELOG, AGENTS, `.env.example`, or screenshots.

A useful local layout:

```text
.local/
├── README.md          # warning + index
├── state.env          # project, region, service URL, SQL connection, budget, quota
├── secrets.env        # API keys, database password (chmod 600)
├── runbook.sh         # smoke/logs/pause/resume/redeploy/seed/demo-seed/teardown
├── deployment.md      # what was created, command history, known issues
└── logs/              # deploy and seed logs
```

## Auth model for a public demo

- **API key (current default).** The deploy script creates two secrets: `springtime-api-key`
  (read-only when the admin key is set) and `springtime-admin-key`. Expose only the read-only key to
  a frontend or interviewer; the admin key stays private.
- **IAP** is Google's Identity-Aware Proxy: it authenticates users at an HTTPS load balancer before
  the request reaches Cloud Run. It is the easiest way to make a *private* clickable demo (Google
  sign-in), but it requires a load balancer (~$18+/month) and infrastructure wiring. Not needed for
  a public demo.
- **OIDC** is token-based application auth: the service validates a JWT from an issuer you configure
  (Google, GCIP, Auth0, Keycloak). It is the right choice for API clients or when the app is not
  behind a GCP load balancer. No proxy and no extra GCP cost.
- For the public portfolio demo: keep `--allow-unauthenticated` on Cloud Run, require the read-only
  API key in the app, and leave IAP/OIDC off. Add IAP later only if you want a gated demo.

## Optional durable events (Pub/Sub)

The demo uses the in-memory broker, which the app logs as a warning at boot. To make events durable:

```bash
gcloud pubsub topics create retail-events
gcloud pubsub topics create retail-events-dlq
gcloud pubsub subscriptions create retail-events-worker \
  --topic=retail-events --ack-deadline=60 \
  --min-retry-delay=10s --max-retry-delay=600s \
  --dead-letter-topic=retail-events-dlq --max-delivery-attempts=5
```

Grant the runtime service account `roles/pubsub.publisher` on the topic and
`roles/pubsub.subscriber` on the subscription, then redeploy with
`MESSAGING_PUBSUB_ENABLED=true`. Run the consumer as its own Cloud Run service with
`MESSAGING_CONSUMER_ENABLED=true` and the read API with it `false`.

## Boot diagnostics

Every start emits one JSON summary line from `StartupReporter` plus warnings for degraded choices:

```
springtime ready: profile=... project=... dataset=... database=... flyway=...
broker=in-memory consumer=local supplier=local projection=false tracing=true otlp=... docs=true auth=api-key adminKey=false
```

Warnings name the exact fix: non-durable broker, localhost OTLP endpoint, single key with operator
authority, disabled outbox/worker, startup Flyway, and public springdoc. The datasource URL is
redacted and keys are never logged.

## Confirmation guardrails

Billable or destructive scripts stop and ask for a typed confirmation. Non-interactive automation
must opt in explicitly; an accidental run aborts.

| Action | Interactive prompt | Non-interactive override | Effect |
| --- | --- | --- | --- |
| `deploy/gcp/deploy.sh` | type `DEPLOY` | `CONFIRM_DEPLOY=yes` | Creates/updates billable resources |
| `deploy/gcp/teardown.sh` | (guard) | `CONFIRM=yes` | Deletes service, DB, dataset, registry, secrets |
| `ops/chaos/run-all.sh` and each scenario | type `CHAOS` | `CONFIRM_CHAOS=yes` | Stops/restarts local dependencies |
| `ops/demo/seed-decisions.sh` | type `CREATE` | `CONFIRM_SEED=yes` | Creates demo run/PO records |
| `make retail-seed` | warning only | — | Replaces demo BigQuery tables |

The local `.local/runbook.sh` adds prompts for `pause`, `redeploy`, `seed`, `demo-seed`, and
`teardown`; set `SPRINGTIME_ASSUME_YES=1` to bypass them in a scripted session.

## Operational notes

- **Migrations** run on first start because `FLYWAY_ENABLED=true` for the demo. Production should run
  migrations as a separate deploy job per `AGENTS.md`.
- **Readiness** includes the BigQuery dry run, so a missing dataset or broken ADC makes readiness
  fail while liveness stays green.
- **Pub/Sub** is disabled (`MESSAGING_PUBSUB_ENABLED=false`) for the single-instance demo; the local
  broker is non-durable. For durability, create the topics/subscription and set the Pub/Sub flags,
  then deploy the consumer as its own service.
- **Auth**: `--allow-unauthenticated` lets Cloud Run accept the request, but the application still
  requires `X-API-Key` (or OIDC). For a public portfolio demo that is the intended trade-off; for
  anything shared, put IAP or OIDC in front and set `SPRING_PROFILES_ACTIVE=prod` with OIDC.
- **Supplier gateway** stays `local` (a demo adapter). The production profile rejects it on purpose.
- **Tracing** is disabled by default in the script unless `OTEL_EXPORTER_OTLP_ENDPOINT` is provided.

## What to connect

- `PROJECT_ID` and a billing account.
- BigQuery location (default `US`); keep Cloud SQL in the same region as Cloud Run.
- Optional: OTLP endpoint for Grafana Cloud/your collector, an Alertmanager route (webhook/Slack/
  PagerDuty), and a custom domain.
