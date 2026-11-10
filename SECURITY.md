# Security policy

## Supported versions

Only the latest release on `main` is supported. This project is pre-1.0; security fixes are applied
to the current line and released as a new patch.

## Reporting a vulnerability

Please **do not** open a public issue for a suspected vulnerability.

Use GitHub's private "Report a vulnerability" flow for this repository (Security → Advisories →
Report a vulnerability). If that is unavailable, contact the maintainer directly. Include:

- affected endpoint or component
- reproduction steps or a proof of concept
- impact (data exposure, cost abuse, availability, credential exposure)
- any suggested mitigation

You should receive an acknowledgement within a few business days. Please allow time for a fix and a
coordinated disclosure before publishing details.

## Authentication model

- **Local:** a 256-bit shared secret in `X-API-Key`, compared in constant time. Without
  `ADMIN_API_KEY` it grants `ROLE_API`; with it, `API_KEY` grants read-only scopes
  (`SCOPE_retail.read`, `SCOPE_bigquery.read`) and only `ADMIN_API_KEY` grants operator authority.
  `make env` generates one and `make key` rotates it; the old key stays in `API_KEY_PREVIOUS` during
  the overlap.
- **Production:** OIDC/JWT bearer tokens through Spring Security's resource-server support. Tokens
  need `bigquery.query`/`bigquery.read` scopes or a mapped `roles` claim for operator/query access.
  A Workspace `hd` domain or service-account `sub` grants only `SCOPE_retail.read`. Configure the
  issuer and audience with
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` and
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES`.
- The application refuses to start when neither mechanism is configured. With the `prod` (or `production`) profile,
  a configured API key is rejected unless `ALLOW_API_KEY_IN_PRODUCTION=true`. There is no Basic
  auth, form login, session, or CSRF token.
- Liveness/readiness and (while enabled) OpenAPI are public; everything else requires auth and an
  explicit authority. Unmatched routes are denied. `hd` is honored only for Google issuers.

## Scope and threat model

This service is a thin BigQuery adapter with a **read API** and a **narrow append-only ledger
writer**. It is **not** a multi-tenant authorization boundary by default:

- The runtime identity's BigQuery IAM roles are the real data boundary.
- `BIGQUERY_READ_ONLY` is a keyword guard, not a SQL parser.
- `maximumBytesBilled` limits one job; it does not limit the number of jobs.
- `API_KEY` is a coarse shared secret, not per-user identity.
- The global concurrency semaphore is not a per-client rate limit.
- All owned BigQuery writes go through `BigQueryDmlGateway` (ledger batch, dead-letter
  persist/replay, replenishment run/proposal/decision, projection refresh).
  `BIGQUERY_READ_ONLY=true` restricts only `/api/v1/query`; the retail write/admin endpoints still
  write under the writer identity. If an operator disables read-only mode, `/query` also becomes a
  write surface via the generic gateway; keep it behind a separate admin identity. In
  production, run the event consumer as a separate worker with a dataset-scoped writer identity,
  while the public API keeps a read-only identity and `MESSAGING_CONSUMER_ENABLED=false`. A single
  demo deployment may use one identity with `dataEditor`; do not expose that identity to untrusted
  callers.
- Dead-letter replay, projection refresh, proposal decisions, and purchase-order submission are
  write/admin operations. Require `retail.write`/`retail.admin`, audit the operator action, and run
  projection writes under the worker identity.
- The default Compose stack binds to `127.0.0.1` and mounts the developer's ADC.

Run it with a dedicated read-only API service account, a separate ledger-worker identity, keep the
API private, and put authentication, per-key rate limiting, and TLS at the edge before exposing it
beyond your machine. Do not commit `application_default_credentials.json`, service-account keys,
`.env` files, or `secrets/`.
