# ADR 0004 — BigQuery gateway and the schema source of truth

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The service is a stateless BigQuery adapter, not a relational application. BigQuery has no JPA-style
entity identity, transactions, relationships, or startup schema. An ORM/Spring Data layer would
misrepresent the resource and hide scan cost.

At the same time, services should not call the BigQuery SDK directly, and query results and table
schemas must not be mapped by two drifting code paths.

## Decisions

- **No JPA, Spring Data, or Postgres control plane now.** Add Postgres + Flyway only when durable
  state appears: async jobs, idempotency keys, API clients/quotas, saved queries, or audit logs.
- **`BigQueryGateway` is the application port.** `GoogleBigQueryGateway` owns SDK calls, pagination
  options, schema field selection, and query-result retrieval. Services depend on the gateway.
- **BigQuery is the canonical database-schema source.** Table schemas come from
  `getTable(..., SCHEMA)`/`Table.getDefinition().getSchema()`, never from a checked-in duplicate.
- **One API schema path.** `BigQueryService.columns(Schema)` and `FieldValueConverter` share the
  column/type model; table schemas expose nested `subFields` and a deterministic `schemaHash`.
- **Strict parameter types.** `ParameterType` is a closed enum with wire-compatible names and
  aliases; invalid types fail at the API boundary and OpenAPI publishes the enum.
- **Result values are dynamic by nature.** Rows remain `Map<String,Object>` with schema-driven typed
  conversion; generated entities for arbitrary SQL would be dishonest.
- **No schema cache by default.** Add a TTL cache in the catalog layer only when measured; return
  `source`/`schemaHash` and support `refresh=true` if cached.

## Consequences

- The gateway is a thin seam today with SDK types in its signatures; a later refactor can move to
  neutral records without changing HTTP clients.
- `schemaHash` gives clients/caches a stable schema version even though BigQuery has no user-assigned
  table schema version.
- A checked-in resource allowlist with optional schema-hash validation is a later feature, not a
  schema source.
- JPA/ORM tooling is explicitly out of scope for BigQuery; a control-plane database is a separate,
  later decision.
