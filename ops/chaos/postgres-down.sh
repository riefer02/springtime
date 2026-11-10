#!/usr/bin/env bash
# Postgres is the control plane. Stopping it must degrade control-plane endpoints to a retryable
# 503 and take readiness down, while liveness stays green so the orchestrator keeps the pod.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
confirm_chaos

printf '\n== postgres-down ==\n'
"${COMPOSE[@]}" stop postgres >/dev/null

# Readiness also includes BigQuery, so it can be non-200 without credentials. The invariant here is
# "not ready", plus a mapped 503 on the control-plane path.
wait_for_not_ok /actuator/health/readiness 90 || fail "readiness stayed 200 during a Postgres outage"

code="$(status_of GET /api/v1/retail/purchase-orders)"
assert_status 503 "$code" "purchase orders during Postgres outage"
assert_code SERVICE_UNAVAILABLE "control-plane outage is retryable"

"${COMPOSE[@]}" start postgres >/dev/null
wait_for_ok /api/v1/retail/purchase-orders 120 || fail "control plane did not recover"
code="$(status_of GET /api/v1/retail/purchase-orders)"
assert_status 200 "$code" "purchase orders after Postgres recovery"
