# Google authentication setup

The app validates OIDC/JWT tokens with Spring Security 7. It does not store passwords, issue tokens,
or manage MFA. Use Google as the identity provider.

## Recommended paths

| Client | Recommendation |
| --- | --- |
| User-facing web/mobile | **Google Identity Platform (GCIP)** with Google Workspace as the first provider; map a `roles` custom claim to `SCOPE_bigquery.*`. |
| Internal-only users, simple | **Google Sign-In / Workspace OIDC** directly (`https://accounts.google.com`), restrict with an `hd` domain allow-list. |
| Internal browser-only app | **IAP direct on Cloud Run** (optional; different token header/decoder). |
| Service-to-service | Cloud Run IAM + **Google service-account ID tokens**; **Workload Identity Federation** outside GCP. |
| Local dev | `make key` API key; for OIDC use a real `gcloud auth print-identity-token` or a local OIDC container. |

## Configure the app

```bash
# Google user ID tokens
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://accounts.google.com
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=https://your-api.example.com
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWS_ALGORITHMS=RS256

# GCIP alternative
# SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://securetoken.google.com/PROJECT_ID
# SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=PROJECT_ID

# Authority mapping
OIDC_ROLES_CLAIM=roles
OIDC_ALLOWED_DOMAINS=example.com
OIDC_ALLOWED_SUBJECTS=1234567890

# Alternative to issuer discovery
# SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=https://issuer.example.com/jwks
```

In production, activate the `prod` (or `production`) profile. A configured `API_KEY` then fails
startup unless `ALLOW_API_KEY_IN_PRODUCTION=true`; prefer OIDC and leave the API key to local use.
If API-key auth is explicitly enabled in production, set a distinct `ADMIN_API_KEY`; `API_KEY`
then carries read-only scopes and `ADMIN_API_KEY` carries operator/admin authority.

`roles` values map to `SCOPE_<role>`; the app authorizes
`POST /api/v1/query` and `/api/v1/jobs/**` with `SCOPE_bigquery.query`, and catalog endpoints with
`SCOPE_bigquery.read` or `SCOPE_bigquery.query`. Retail reads use `SCOPE_retail.read`, event intake,
replenishment-run creation, proposal decisions, and purchase-order creation use
`SCOPE_retail.write`, as do purchase-order submit/cancel/transmit. Reconciliation/projection
refresh/dead-letter replay and supplier retry/ack use `SCOPE_retail.admin`. A Workspace `hd` in `OIDC_ALLOWED_DOMAINS` or a service-account `sub` in
`OIDC_ALLOWED_SUBJECTS` grants only `SCOPE_retail.read`; operator and query endpoints still require
an explicit roles claim.

Google ID tokens do **not** carry OAuth scopes. If you skip the custom claim/allow-list mapping, a
valid Google token authenticates but receives `403 ACCESS_DENIED`.

## GCIP custom claims

Set a `roles` claim on users/tokens, for example `["bigquery.read"]` or
`["bigquery.query","bigquery.read"]`. Custom claims are limited to about 1000 bytes and propagate on
token refresh.

## Service-to-service

```bash
TOKEN=$(gcloud auth print-identity-token \
  --impersonate-service-account=caller@PROJECT.iam.gserviceaccount.com \
  --audiences='https://springtime-api-abc123-uc.a.run.app')

curl -H "Authorization: Bearer $TOKEN" \
  https://springtime-api-abc123-uc.a.run.app/api/v1/datasets
```

- Target Cloud Run: `--no-allow-unauthenticated`; caller SA gets `roles/run.invoker`.
- Audience validation proves the token was minted for your API; **Cloud Run IAM is the authorization
  check**. A service account can request any audience.
- Use WIF + SA impersonation outside GCP; do not create JSON keys.
- Use the token `sub` for identity; `email` is optional.

## Local OIDC test

```bash
TOKEN=$(gcloud auth print-identity-token --audiences=http://localhost:8080)
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://accounts.google.com \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES=http://localhost:8080 \
  make run
curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/datasets
```

For an offline local IdP, use mock-oauth2-server or Keycloak and set
`ALLOW_INSECURE_OIDC=true` for an `http://` issuer.

## API-key rotation

- `make key` generates a new `API_KEY` and moves the old value to `API_KEY_PREVIOUS`.
- Both keys are accepted during the overlap; remove `API_KEY_PREVIOUS` once clients are migrated.
- API keys are a shared secret, not user identity. Prefer OIDC for shared deployments.

## IAM

- API runtime SA: `roles/bigquery.jobUser` on the project + dataset-scoped
  `roles/bigquery.dataViewer`; publish-only `roles/pubsub.publisher` on the event topic.
- Event worker SA (separate): `roles/bigquery.jobUser` + dataset-scoped `roles/bigquery.dataEditor`
  on the retail dataset, `roles/pubsub.subscriber` on the subscription, and
  `roles/pubsub.publisher` on the dead-letter topic. The public API deployment sets
  `MESSAGING_CONSUMER_ENABLED=false`; the worker sets it to `true`.
- Migrator SA (separate): dataset-scoped `roles/bigquery.dataEditor` + project `jobUser`.
- Cloud Run deployer: `roles/run.developer`, `roles/iam.serviceAccountUser` on the runtime SA.
- M2M caller: `roles/run.invoker` on the target service.
- Do not grant `roles/bigquery.admin`/`dataOwner` to the API runtime. Do not run the public API and
  the ledger writer under the same identity outside local/demo use.

## Do not double-validate

Pick one enforcement path per route: app-validated bearer tokens, IAP, or API Gateway. Stacking
independent authn layers without a trust model creates bypasses and confusion.
