#!/usr/bin/env bash
# Deploy the demo to Cloud Run with a Cloud SQL Postgres control plane and BigQuery analytics.
# This is the "smallest useful GCP footprint" path: no GKE, no Dataflow, no Composer.
#
# Usage:
#   PROJECT_ID=my-project ./deploy/gcp/deploy.sh
#
# Optional environment:
#   REGION=us-central1            SERVICE=springtime-api       DB_INSTANCE=springtime-pg
#   DB_NAME=springtime            DB_USER=springtime           DATASET=retail_demo
#   REPO=springtime               SA_NAME=springtime-runtime   SEED_DATA=1
#   API_KEY=...                   ADMIN_API_KEY=...            DB_PASSWORD=...
#   OTEL_EXPORTER_OTLP_ENDPOINT=...
#
# Requires: gcloud (authenticated), bq, openssl, and a project with billing enabled.
set -euo pipefail

if [[ "${CONFIRM_DEPLOY:-}" != "yes" ]]; then
  printf '\nWARNING: this creates or updates billable GCP resources.\n'
  printf '  Cloud SQL db-f1-micro is the main cost (~$8-13/month while running).\n'
  printf '  Cloud Run scales to zero; BigQuery stays in the free tier at demo volume.\n'
  printf '  Existing API keys, the DB password, and the schema are preserved.\n\n'
  if [[ -t 0 ]]; then
    read -r -p "Type DEPLOY to continue: " answer
    [[ "$answer" == "DEPLOY" ]] || { echo "Aborted."; exit 1; }
  else
    printf 'Non-interactive shell: set CONFIRM_DEPLOY=yes to run.\n' >&2
    exit 1
  fi
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-springtime-api}"
DB_INSTANCE="${DB_INSTANCE:-springtime-pg}"
DB_NAME="${DB_NAME:-springtime}"
DB_USER="${DB_USER:-springtime}"
DATASET="${DATASET:-retail_demo}"
BQ_LOCATION="${BQ_LOCATION:-US}"
REPO="${REPO:-springtime}"
SA_NAME="${SA_NAME:-springtime-runtime}"
SEED_DATA="${SEED_DATA:-1}"

SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"
IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REPO}/${SERVICE}:$(date +%Y%m%d-%H%M%S)"

say() { printf '\n== %s\n' "$*"; }

command -v gcloud >/dev/null || { echo "gcloud is required" >&2; exit 1; }
gcloud config set project "$PROJECT_ID" >/dev/null

say "Enabling APIs"
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  bigquery.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  cloudbuild.googleapis.com \
  logging.googleapis.com \
  monitoring.googleapis.com \
  cloudtrace.googleapis.com \
  iam.googleapis.com >/dev/null

say "Service account"
if ! gcloud iam service-accounts describe "$SA_EMAIL" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$SA_NAME" \
    --display-name="Springtime runtime" >/dev/null
fi
for role in \
  roles/cloudsql.client \
  roles/secretmanager.secretAccessor \
  roles/bigquery.jobUser \
  roles/bigquery.dataEditor \
  roles/logging.logWriter \
  roles/monitoring.metricWriter \
  roles/cloudtrace.agent \
  roles/artifactregistry.reader; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${SA_EMAIL}" --role="$role" --condition=None >/dev/null
done

say "Artifact Registry"
if ! gcloud artifacts repositories describe "$REPO" --location="$REGION" >/dev/null 2>&1; then
  gcloud artifacts repositories create "$REPO" \
    --repository-format=docker --location="$REGION" \
    --description="Springtime images" >/dev/null
fi

say "Cloud SQL (db-f1-micro, 10 GB SSD)"
if ! gcloud sql instances describe "$DB_INSTANCE" >/dev/null 2>&1; then
  gcloud sql instances create "$DB_INSTANCE" \
    --database-version=POSTGRES_16 \
    --edition=ENTERPRISE \
    --tier=db-f1-micro \
    --region="$REGION" \
    --storage-size=10GB \
    --storage-type=SSD \
    --availability-type=ZONAL \
    --backup \
    --retained-backups-count=3 >/dev/null
fi

if ! gcloud sql databases describe "$DB_NAME" --instance="$DB_INSTANCE" >/dev/null 2>&1; then
  gcloud sql databases create "$DB_NAME" --instance="$DB_INSTANCE" >/dev/null
fi

read_secret() {
  gcloud secrets versions access latest --secret="$1" 2>/dev/null || true
}

# Reuse the existing database password unless the caller explicitly rotates it. A redeploy that
# silently changed the password would break every already-issued secret and external client.
DB_PASSWORD="${DB_PASSWORD:-$(read_secret springtime-db-password)}"
DB_PASSWORD="${DB_PASSWORD:-$(openssl rand -hex 24)}"
if ! gcloud sql users list --instance="$DB_INSTANCE" --format='value(name)' | grep -qx "$DB_USER"; then
  gcloud sql users create "$DB_USER" --instance="$DB_INSTANCE" --password="$DB_PASSWORD" >/dev/null
elif [[ "${ROTATE_DB_PASSWORD:-0}" == "1" ]]; then
  gcloud sql users set-password "$DB_USER" --instance="$DB_INSTANCE" --password="$DB_PASSWORD" >/dev/null
fi

say "BigQuery dataset"
bq --location="$BQ_LOCATION" mk --dataset "${PROJECT_ID}:${DATASET}" 2>/dev/null || true
if [[ "$SEED_DATA" == "1" ]]; then
  ( cd "$ROOT" && GOOGLE_CLOUD_PROJECT="$PROJECT_ID" RETAIL_DATASET="$DATASET" \
      BIGQUERY_LOCATION="$BQ_LOCATION" make retail-schema retail-seed )
else
  echo "Skipping schema/seed (SEED_DATA=0). Run make retail-schema retail-seed later."
fi

say "Secrets"
# Reuse issued keys across redeploys. Rotate explicitly by setting API_KEY/ADMIN_API_KEY.
API_KEY="${API_KEY:-$(read_secret springtime-api-key)}"
API_KEY="${API_KEY:-$(openssl rand -hex 32)}"
ADMIN_API_KEY="${ADMIN_API_KEY:-$(read_secret springtime-admin-key)}"
ADMIN_API_KEY="${ADMIN_API_KEY:-$(openssl rand -hex 32)}"
upsert_secret() {
  local name="$1" value="$2" current
  current="$(read_secret "$name")"
  if [[ "$current" == "$value" ]]; then
    return 0
  fi
  if gcloud secrets describe "$name" >/dev/null 2>&1; then
    printf '%s' "$value" | gcloud secrets versions add "$name" --data-file=- >/dev/null
  else
    printf '%s' "$value" | gcloud secrets create "$name" --data-file=- \
      --replication-policy=automatic >/dev/null
  fi
}
# API_KEY is the public read-only credential when ADMIN_API_KEY is set; keep the admin key private.
upsert_secret springtime-api-key "$API_KEY"
upsert_secret springtime-admin-key "$ADMIN_API_KEY"
upsert_secret springtime-db-password "$DB_PASSWORD"

say "Build image"
gcloud builds submit --tag "$IMAGE" "$ROOT"

say "Deploy Cloud Run"
CONNECTION="$(gcloud sql instances describe "$DB_INSTANCE" --format='value(connectionName)')"
DATABASE_URL="jdbc:postgresql:///${DB_NAME}?cloudSqlInstance=${CONNECTION}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC"

if [[ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]]; then
  TRACING_ENABLED="true"
else
  TRACING_ENABLED="false"
fi

gcloud run deploy "$SERVICE" \
  --image="$IMAGE" \
  --region="$REGION" \
  --platform=managed \
  --allow-unauthenticated \
  --service-account="$SA_EMAIL" \
  --add-cloudsql-instances="$CONNECTION" \
  --min-instances=0 \
  --max-instances=2 \
  --cpu=1 \
  --memory=1Gi \
  --concurrency=40 \
  --timeout=300 \
  --cpu-boost \
  --set-secrets="API_KEY=springtime-api-key:latest,ADMIN_API_KEY=springtime-admin-key:latest,DATABASE_PASSWORD=springtime-db-password:latest" \
  --set-env-vars="^@^GOOGLE_CLOUD_PROJECT=${PROJECT_ID}@BIGQUERY_LOCATION=${BQ_LOCATION}@RETAIL_DATASET=${DATASET}@DATABASE_URL=${DATABASE_URL}@DATABASE_USERNAME=${DB_USER}@FLYWAY_ENABLED=true@SUPPLIER_GATEWAY=local@SUPPLIER_OUTBOX_WORKER_ENABLED=true@MESSAGING_PUBSUB_ENABLED=false@MESSAGING_LOCAL_CONSUMER_ENABLED=true@RETAIL_PROJECTION_ENABLED=false@SPRINGDOC_ENABLED=true@TRACING_ENABLED=${TRACING_ENABLED}@OTEL_EXPORTER_OTLP_ENDPOINT=${OTEL_EXPORTER_OTLP_ENDPOINT:-}" >/dev/null

URL="$(gcloud run services describe "$SERVICE" --region="$REGION" --format='value(status.url)')"
say "Deployed"
echo "URL:   $URL"
echo "Read-only key: springtime-api-key (latest); operator key: springtime-admin-key (latest)"
echo "Smoke: curl -s -o /dev/null -w '%{http_code}' -H \"X-API-Key: <key>\" $URL/api/v1/retail/purchase-orders"
printf '\nCost guardrails to set now:\n'
printf '  - Budget alert:  gcloud billing budgets create --billing-account=<id> --display-name=springtime --budget-amount=20USD --threshold-rule=percent=0.5 --threshold-rule=percent=1.0\n'
printf '  - BigQuery cap:  set a daily bytes-scanned custom quota in the console (see docs/deployment/gcp.md)\n'
printf '  - Pause the DB:  gcloud sql instances patch %s --activation-policy=NEVER\n' "$DB_INSTANCE"
