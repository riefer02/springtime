#!/usr/bin/env bash
# BigQuery is the analytical plane. An unreachable project must surface as a mapped upstream error
# (502/503) and take readiness down, never as a 500, and must recover cleanly.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
confirm_chaos

printf '\n== bigquery-unavailable ==\n'
"${COMPOSE[@]}" stop api >/dev/null
GOOGLE_CLOUD_PROJECT=definitely-not-a-project \
  "${COMPOSE[@]}" up -d api >/dev/null
wait_for_liveness
# Readiness includes the BigQuery dry run; without credentials it is already non-200, so only assert
# that liveness stays green and the failure is mapped on the read path.
wait_for_not_ok /actuator/health/readiness 90 || fail "readiness stayed 200 with a broken BigQuery project"

code="$(status_of GET /api/v1/retail/stores)"
assert_status_in "retail read with a broken BigQuery project" "$code" 502 503
if grep -q '"code":"INTERNAL_ERROR"' /tmp/chaos-body.json; then
  fail "BigQuery outage leaked as INTERNAL_ERROR instead of a mapped upstream code"
fi

restore_api
wait_for_ok /api/v1/retail/purchase-orders 120 || fail "control plane did not recover"
code="$(status_of GET /api/v1/retail/purchase-orders)"
assert_status 200 "$code" "control plane after BigQuery recovery"
