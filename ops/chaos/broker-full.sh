#!/usr/bin/env bash
# A full local broker must reject work before accepting it (no silent drop). The local consumer is
# disabled so the queue cannot drain during the test.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
confirm_chaos

printf '\n== broker-full ==\n'
"${COMPOSE[@]}" stop api >/dev/null
MESSAGING_MAX_IN_MEMORY_MESSAGES=1 MESSAGING_LOCAL_CONSUMER_ENABLED=false \
  "${COMPOSE[@]}" up -d api >/dev/null
wait_for_liveness

overloaded=0
for i in 1 2 3 4; do
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  body="{\"eventId\":\"chaos-broker-$i\",\"eventType\":\"SALE\",\"occurredAt\":\"$now\",\"storeId\":\"ST-001\",\"productId\":\"SKU-0001\",\"quantity\":1}"
  code="$(status_of POST /api/v1/retail/events "$body")"
  if [[ "$code" == "503" ]]; then
    overloaded=$((overloaded + 1))
    assert_code SERVICE_UNAVAILABLE "broker-full rejection is retryable"
  fi
done

if [[ "$overloaded" -ge 1 ]]; then
  pass "broker failed closed ($overloaded/4 rejected)"
else
  fail "broker never rejected work despite capacity 1; events may be silently dropped"
fi

restore_api
