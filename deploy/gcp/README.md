# GCP deployment scripts

`deploy.sh` provisions the smallest useful footprint (Cloud Run, Cloud SQL Postgres 16, BigQuery,
Artifact Registry, Secret Manager) and deploys the image. `teardown.sh` removes it.

```bash
PROJECT_ID=my-project ./deploy/gcp/deploy.sh          # prompts: type DEPLOY
CONFIRM_DEPLOY=yes PROJECT_ID=my-project ./deploy/gcp/deploy.sh   # non-interactive
CONFIRM=yes PROJECT_ID=my-project ./deploy/gcp/teardown.sh
```

Read [docs/deployment/gcp.md](../../docs/deployment/gcp.md) first: it covers cost, guardrails,
pausing Cloud SQL, telemetry hosting, and the operational differences from local Compose.

The scripts are intentionally plain `gcloud`/`bq` commands rather than Terraform. If this becomes a
long-lived environment, port them to Terraform; the resource set and variables are already explicit.
