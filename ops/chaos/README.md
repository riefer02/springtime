# Failure injection

Local, repeatable checks for the failure matrix in
[docs/operations/README.md](../../docs/operations/README.md). Each script asserts a specific
observable outcome, not just "no crash": the status code, the RFC 9457 `code`, and recovery.

```bash
make up          # stack running
make chaos       # prompts; type CHAOS. Restores the API between scenarios.
CONFIRM_CHAOS=yes make chaos   # non-interactive
```

Individual scenarios:

```bash
bash ops/chaos/postgres-down.sh
bash ops/chaos/broker-full.sh
bash ops/chaos/bigquery-unavailable.sh
```

| Scenario | Injection | Expected |
| --- | --- | --- |
| `postgres-down.sh` | `docker compose stop postgres` | Readiness 503; control-plane reads 503 `SERVICE_UNAVAILABLE`; recovery to 200 |
| `broker-full.sh` | Capacity 1, consumer disabled, 4 publishes | At least one 503 with no accepted-then-dropped event |
| `bigquery-unavailable.sh` | Invalid GCP project | Readiness 503; retail reads 502/503 with a mapped code, never `INTERNAL_ERROR`; recovery |

Requirements: `docker`, `curl`, `bash`, and a populated `.env` with `API_KEY`. The scripts recreate
the local `api` container when injecting environment changes and always restore it.

These are deliberately local: they prove the application's *own* failure behavior. Network-level
chaos against real Google infrastructure (latency, quota exhaustion, partial regional failure)
belongs in a staging project once credentials are connected.
