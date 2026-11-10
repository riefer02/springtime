# Contributing

## Prerequisites

- JDK 21 (the Maven wrapper is included)
- Docker with Compose v2
- `gcloud` CLI for real BigQuery access

## Local workflow

```bash
cp .env.example .env          # or: make env (also generates an API_KEY)
gcloud auth application-default login
make up                       # loopback-only Compose stack
make logs
make test                     # unit + MVC + security + contract tests, no GCP calls
make it                       # opt-in real-BigQuery integration tests
make versions                 # check for dependency/plugin updates
make openapi                  # regenerate the committed OpenAPI contract
```

`make run` runs the app with Maven and loads `.env` if present. `make key` rotates the local API key.

## Code expectations

- Java 21, Spring Boot 4.1.x, Maven.
- Constructor injection; no field injection.
- Records for request/response types; validate at the HTTP edge.
- Keep BigQuery client types out of controllers.
- Use RFC 9457 (`ProblemDetail`, `application/problem+json`) for errors.
- Do not log SQL text, row data, tokens, or credentials. Job id, client id, and byte counts are fine.
- Add a test for every behavior change. Prefer unit/MVC tests; the real-GCP test is opt-in.
- Keep the security chain, read-only guard, byte ceiling, row ceiling, request/parameter caps, and
  concurrency cap intact when adding endpoints.
- Never log or commit `X-API-Key`, bearer tokens, or ADC file contents.
- API changes: run `make openapi`, review the JSON diff, and commit it. Error-code changes must update
  `docs/api/errors.md` and `CHANGELOG.md`.

## Version policy

- Stay on the newest stable Spring Boot minor that receives OSS support. As of this change that is
  **4.1.x**; 3.5 reached OSS end-of-life on 2026-06-30.
- Let the Spring Boot BOM manage Spring, Jackson, Tomcat, and Micrometer versions.
- Pin the Google Cloud client explicitly and review upgrades in a dedicated PR.
- Dependabot proposes Maven, Docker, and GitHub Actions updates weekly. Merge after CI is green.
- Record platform-level changes in `docs/adr/`.

## Commits and pull requests

- Conventional Commits are preferred (`feat:`, `fix:`, `docs:`, `chore:`, `test:`).
- Keep PRs focused; include the problem, the approach, and the verification performed.
- Required before merge: CI green (`./mvnw -B -ntp verify`, container build + smoke test), no new
  secrets, docs updated when behavior changes.
- Do not commit `.env`, `secrets/`, ADC files, or generated build output.

## Definition of done

- [ ] Tests cover the change; `make verify` is green.
- [ ] `docker compose up --build` works on a clean checkout.
- [ ] Error responses remain ProblemDetail-shaped and do not leak internals.
- [ ] Cost, row, and concurrency guards still apply.
- [ ] README/OpenAPI reflect any contract change.
- [ ] No credentials, SQL text, or row data in logs.
