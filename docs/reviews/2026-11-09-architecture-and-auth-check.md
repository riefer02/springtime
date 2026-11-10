# Architecture, agent files, migrations, and auth — review note

- **Date:** 2026-11-09
- **Follows:** initial review, gateway hardening, contract/resource-layer note.
- **Scope:** architecture re-check, project agent instructions, BigQuery migrations/rollback,
  source-of-truth alignment, and Google-native authentication.

## Verdict

Architecture is sound for a single-tenant, read-only BigQuery adapter. The biggest risks were not
the overall shape; they were contract/auth/test seams. Those are fixed. Do **not** add JPA, a
Postgres control plane, a migration runner, or a schema cache until a feature actually needs them.

## Fixed in this pass

| Area | Change |
| --- | --- |
| **Integration test** | The opt-in BigQuery test sent a different API key than it configured; fixed, so the real-cloud path can actually pass. |
| **Contract** | `QueryParameter.value` no longer publishes `type: null`; it is a scalar union. `ProblemDetail` now documents `errors`; 405/406/409/415 and `X-BigQuery-Job-Id` are in the spec. |
| **Limits** | DTO hard ceilings align with BigQuery limits; runtime defaults remain stricter and documented. |
| **Auth** | Multi-key rotation via `API_KEY_PREVIOUS`; invalid key no longer blocks a valid bearer; Google/GCIP authority mapping (`roles`, `hd`, `sub`); `AuthenticationContextFilter` moved before authorization; both auth schemes advertised in `WWW-Authenticate`. |
| **Docs** | springdoc is disabled by default in the base config; Compose/CI opt in. |
| **Errors** | Invalid `pageSize=abc` is now `400 INVALID_REQUEST`, not 500. |
| **Validation** | Zero/negative durations rejected at startup; issuer + JWK-set is allowed (Boot validates issuer). |
| **Schema** | Missing table schemas no longer look like a valid empty schema; `schemaHash` length-prefixes parts to avoid delimiter collisions. |
| **Types** | `ParameterType` moved out of `service` (no package cycle); NUMERIC/BIGNUMERIC inputs must be strings; NUMERIC/BIGNUMERIC outputs are strings for JavaScript safety. |
| **Agent files** | `AGENTS.md` is the single canonical file. No `CLAUDE.md`, Cursor rules, Copilot copy, or `.opencode/` yet — those tools read `AGENTS.md`, and duplication drifts. |
| **Migrations** | ADR 0005 + `db/README.md` define forward-only SQL, ledger, expand/contract, snapshots, and separate deploy job. No runner until owned DDL exists. |
| **Auth docs** | ADR 0006 + `docs/auth/README.md` recommend GCIP/Workspace OIDC for users, service-account ID tokens/WIF for M2M, IAP for internal browser apps, and API keys only locally. |

## Deliberately deferred

- Split `BigQueryService` into `QueryService`/`CatalogService` and neutralize gateway records.
- A custom BigQuery migration runner (plan/apply/verify/ledger) — build when the first owned
  dataset/table/view exists.
- Dataform or dbt for analytical joins/views.
- Postgres/Flyway control plane for async jobs, idempotency, clients/quotas, saved queries.
- Per-key rate limits, response-byte caps, async job submit/cancel, custom BigQuery metrics/OTel,
  Terraform/WIF, image scanning/signing, OAS semantic diffing.
- Schema cache/ETag/resource allowlist.

## Verification

- `./mvnw test` → **73 tests, 0 failures**, 3 opt-in/integration skipped.
- Contract drift test passes; OpenAPI 3.1, five paths, problem schema, scalar parameter union.
- No-auth startup prints `STARTUP_CONFIG_INVALID` and exits before listening.
- Container smoke: liveness, contract, 401 shape, 404/405/413, authenticated Prometheus.
- Real BigQuery execution still requires ADC/project credentials; `make it` is ready once available.

## Recommendation for the next step

1. Set up the GCP project and ADC; run `make it` against a tiny demo dataset.
2. If owned BigQuery tables/views appear, implement ADR 0005's runner and a schema contract check.
3. If external clients appear, decide GCIP vs Workspace OIDC and add edge rate limiting/TLS.
4. Only then split services/neutralize the gateway and add idempotency/async jobs.
