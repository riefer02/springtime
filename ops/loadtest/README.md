# Load testing

k6 scenarios that encode the SLOs in [docs/operations/README.md](../../docs/operations/README.md).
A run fails when a threshold is crossed, so this is a gate, not a chart generator.

## Run

The stack must be up (`make up`). The `k6` service is in the observability overlay and runs on the
Compose network as `http://api:8080`.

```bash
make obs-up                 # prometheus + grafana + otel + metrics proxy
make loadtest               # smoke.js: liveness + authenticated list
make loadtest SCRIPT=/scripts/retail-read.js
make loadtest SCRIPT=/scripts/event-intake.js
make loadtest SCRIPT=/scripts/mixed.js
```

You can also run it directly:

```bash
docker compose -f compose.yaml -f compose.observability.yaml --profile loadtest \
  run --rm --no-deps -e K6_SCRIPT=/scripts/mixed.js k6
```

Against a remote environment:

```bash
BASE_URL=https://api.example.com API_KEY=... \
  docker run --rm -i -v "$PWD/ops/loadtest:/scripts:ro" \
  -e BASE_URL -e API_KEY -e K6_SCRIPT=/scripts/smoke.js \
  grafana/k6:0.54.0 run /scripts/smoke.js
```

## Scenarios

| File | What it proves | Prerequisites |
| --- | --- | --- |
| `smoke.js` | Process is alive, auth works, page envelope is intact | API up |
| `event-intake.js` | 20 events/s intake, p95 < 250ms, <0.5% failures | `MESSAGING_LOCAL_CONSUMER_ENABLED=false` for intake-only, or BigQuery for full path |
| `retail-read.js` | Read p95 < 2s across inventory/replenishment/movements/dashboard | Seeded BigQuery dataset |
| `mixed.js` | Reads stay fast while intake runs; verifies separate slot pools | Seeded dataset |

Without BigQuery credentials, `retail-read.js` and `mixed.js` will fail on `503 DATA_NOT_READY` —
that is the correct signal, not a harness bug. Use `smoke.js` and `event-intake.js` with the consumer
disabled until credentials are connected.

## Interpreting results

- `http_req_failed` above 1% means the SLO is already burning budget; check the correlated
  `requestId`/`traceId` in JSON logs.
- Retail p95 rising while `/query` is idle points at BigQuery scan size, not the HTTP layer; look at
  `bytesBilled` and the query in the job history.
- `retail_inventory_movement_batch_pending` approaching 2,000 or the 32 MiB budget means consumers
  cannot keep up; scale out before callers see `LEDGER_OVERLOAD`.
- Event p95 above 250ms with a healthy broker usually means the broker client is blocking on
  publish; compare `surface=retail` job activity.

## Notes

- Event ids are unique per iteration, so retries do not collide with previous runs.
- k6 trends are tagged by endpoint; thresholds can be scoped per tag as in `mixed.js`.
- For repeatable numbers, pin CPU/memory for the API container and run from the same host.
