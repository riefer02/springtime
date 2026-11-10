#!/usr/bin/env bash
# Remove everything deploy/gcp/deploy.sh created. Requires CONFIRM=yes.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
REGION="${REGION:-us-central1}"
SERVICE="${SERVICE:-springtime-api}"
DB_INSTANCE="${DB_INSTANCE:-springtime-pg}"
DATASET="${DATASET:-retail_demo}"
REPO="${REPO:-springtime}"
SA_NAME="${SA_NAME:-springtime-runtime}"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

if [[ "${CONFIRM:-}" != "yes" ]]; then
  echo "This deletes the Cloud Run service, Cloud SQL instance, BigQuery dataset, image repo, and secrets."
  echo "Re-run with CONFIRM=yes PROJECT_ID=$PROJECT_ID"
  exit 1
fi

gcloud config set project "$PROJECT_ID" >/dev/null

gcloud run services delete "$SERVICE" --region="$REGION" --quiet || true
gcloud sql instances delete "$DB_INSTANCE" --quiet || true
bq rm -r -f "${PROJECT_ID}:${DATASET}" || true
gcloud artifacts repositories delete "$REPO" --location="$REGION" --quiet || true
gcloud secrets delete springtime-api-key --quiet || true
gcloud secrets delete springtime-admin-key --quiet || true
gcloud secrets delete springtime-db-password --quiet || true
gcloud iam service-accounts delete "$SA_EMAIL" --quiet || true

echo "Deleted Springtime demo resources in $PROJECT_ID ($REGION)."
