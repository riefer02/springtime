# ADR 0006 — Google-native authentication

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The service is a machine API over BigQuery. It already supports a local shared-secret API key and a
generic Spring Security 7 OIDC/JWT resource server. The owner is new to Google Cloud and wants the
best Google-native setup without building passwords, MFA, or token issuance.

## Decisions

### User-facing clients

Use **Google Identity Platform (GCIP)** as the OIDC issuer, with **Google Workspace / Cloud Identity**
as the first provider. GCIP provides standard OIDC ID tokens, MFA, external SAML/OIDC providers,
multi-tenancy, and custom claims.

- Issuer: `https://securetoken.google.com/<project-id>`
- Audience: `<project-id>`
- Map a custom claim such as `{"roles":["bigquery.query"]}` to `SCOPE_bigquery.query`.
- If every user is internal and custom claims are unnecessary, Google Sign-In directly with
  issuer `https://accounts.google.com`, audience = the OAuth web client ID, and an `hd` allow-list.

### Internal browser-only apps

Use **IAP direct on Cloud Run** if the only client is an internal browser app. IAP handles login and
Google Groups. The app validates `X-Goog-IAP-JWT-Assertion` with a custom ES256 decoder; this is a
later opt-in path, not the default API path.

### Service-to-service

Use **Cloud Run IAM + Google service-account ID tokens**; use **Workload Identity Federation** for
callers outside Google Cloud.

- Issuer: `https://accounts.google.com`
- Audience: the target API URL or a custom audience
- Cloud Run `--no-allow-unauthenticated` + `roles/run.invoker` is the authorization check.
- Audience validation alone is not authorization: a service account may request any audience.

### Local development

Use a generated `API_KEY` (`make key`), with `API_KEY_PREVIOUS` for rotation. For OIDC testing, mint
a real Google ID token:

```bash
gcloud auth print-identity-token --audiences=http://localhost:8080
```

or run a local OIDC provider (mock-oauth2-server/Keycloak) with `ALLOW_INSECURE_OIDC=true`.

### Application responsibilities

The app owns **authorization mapping**, not identity:

- GCIP `roles` claim → `SCOPE_*` authorities.
- Workspace `hd` in `OIDC_ALLOWED_DOMAINS` → `SCOPE_retail.read` (read-only retail access).
- Service-account `sub` in `OIDC_ALLOWED_SUBJECTS` → `SCOPE_retail.read` (read-only retail access).
- The local `X-API-Key` is an operator credential. Under the `prod`/`production` profile it is
  rejected unless `ALLOW_API_KEY_IN_PRODUCTION=true`; OIDC is the production identity.
- Local API keys → `ROLE_API`.
- Stateless Spring Security, no sessions, no Basic, no CSRF.

Identity, MFA, password reset, token signing, JWKS rotation, and SSO belong to the IdP. Rate limits,
WAF, TLS, and per-client API keys belong at the edge (Cloud Armor/API Gateway/IAP).

### IAM

- Runtime: `roles/bigquery.jobUser` on the project + dataset-scoped `roles/bigquery.dataViewer`.
- Migrator (separate identity): dataset-scoped `dataEditor` + project `jobUser`; never on runtime.
- Cloud Run deployer: `roles/run.developer`, `roles/iam.serviceAccountUser` on runtime SA.
- M2M caller: `roles/run.invoker` on the service.
- Use Workload Identity Federation; do not create JSON keys.

## Consequences

- Google ID tokens have no OAuth scopes; the app’s converter is mandatory for authorization.
- Audience validation is required; issuer strings must match exactly (no trailing slash).
- API Gateway/IAP should not be stacked as independent authn layers without a clear trust model.
- API keys are for local/dev and emergency rotation; prefer OIDC for shared deployments.
- The runtime service account remains read-only; IAM is the real data boundary.
