# ADR 0001 — Platform baseline

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

`springtime` is a new, API-only service that executes BigQuery queries and exposes catalog metadata.
It has no database and no frontend. The initial scaffold used Spring Boot 3.5.3, but 3.5 reached
OSS end-of-life on 2026-06-30 (final patch 3.5.16). Starting a new project on an unsupported line
creates immediate security and upgrade debt.

## Decision

- **Java 21** (LTS) with virtual threads enabled.
- **Spring Boot 4.1.2** (current stable OSS line at the time of writing). This brings Spring
  Framework 7.0.x, Jackson 3.1.x, Tomcat 11.0.x, Micrometer 1.17.x, and the Boot 4 modular starters
  (`spring-boot-starter-webmvc`, `spring-boot-starter-micrometer-metrics`).
- **google-cloud-bigquery 2.76.0**, pinned explicitly because Google Cloud is not managed by the
  Spring Boot BOM.
- **Stateless architecture:** one API service, no database, no session state.
- **Application Default Credentials** for local development; Workload Identity / a dedicated
  service account for deployed environments.
- **BigQuery is the enforcement boundary:** least-privilege IAM plus `maximumBytesBilled`. The
  in-app read-only guard is a usability filter, not a security parser.
- **Loopback-only Compose default**; widening the bind address is a deliberate change.
- **Dependency automation:** Dependabot (Maven, Docker, Actions) plus a CI verify/container-smoke
  gate.
- **Version policy:** newest stable, OSS-supported Spring Boot minor; review upgrades in dedicated
  PRs and record platform shifts as ADRs.

## Consequences

- The code uses Boot 4 package/module names (e.g. `org.springframework.boot.webmvc.test.autoconfigure`,
  `org.springframework.boot.health.contributor`) and Jackson 3 on the wire. Most user code is
  unaffected because records, Jakarta Validation, and `ProblemDetail` are stable.
- Spring Boot 3.5-era tutorials and snippets may not apply.
- A future Boot 5 or Spring Framework 8 upgrade will require a scheduled migration spike, not an
  incidental bump.
- `libraries-bom` is intentionally not used. BigQuery and Pub/Sub are Google Cloud clients today
  (ADR 0015 adds a Postgres control plane, superseding the "no database" baseline). Revisit
  if more Google clients are added.
