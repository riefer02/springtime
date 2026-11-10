#!/usr/bin/env bash
# Shared helpers for the local failure-injection checks.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE=(docker compose -f "$ROOT/compose.yaml")
if [[ -f "$ROOT/compose.observability.yaml" ]]; then
  COMPOSE+=(-f "$ROOT/compose.observability.yaml")
fi

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  . "$ROOT/.env"
  set +a
fi

BASE_URL="http://localhost:${API_PORT:-8080}"
: "${API_KEY:?set API_KEY in .env or the environment}"

confirm_chaos() {
  if [[ "${CONFIRM_CHAOS:-}" == "yes" ]]; then
    return 0
  fi
  printf '\nWARNING: this stops or restarts local dependencies (Postgres, the API container).\n'
  printf 'It is destructive only to the local Compose stack; each scenario restores the API.\n'
  if [[ ! -t 0 ]]; then
    printf 'Non-interactive shell: set CONFIRM_CHAOS=yes to run.\n' >&2
    exit 1
  fi
  read -r -p "Type CHAOS to continue: " answer
  [[ "$answer" == "CHAOS" ]] || { echo "Aborted."; exit 1; }
}

pass() { printf '  PASS  %s\n' "$*"; }
fail() { printf '  FAIL  %s\n' "$*" >&2; return 1; }

status_of() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS -o /tmp/chaos-body.json -w '%{http_code}' -X "$method" \
      -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
      -d "$body" "$BASE_URL$path"
  else
    curl -sS -o /tmp/chaos-body.json -w '%{http_code}' -X "$method" \
      -H "X-API-Key: $API_KEY" "$BASE_URL$path"
  fi
}

assert_status() {
  local expected="$1" actual="$2" label="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$label ($actual)"
  else
    printf '  body: %s\n' "$(cat /tmp/chaos-body.json 2>/dev/null || true)" >&2
    fail "$label expected $expected, got $actual"
  fi
}

assert_status_in() {
  local label="$1" actual="$2"
  shift 2
  for expected in "$@"; do
    if [[ "$actual" == "$expected" ]]; then
      pass "$label ($actual)"
      return 0
    fi
  done
  printf '  body: %s\n' "$(cat /tmp/chaos-body.json 2>/dev/null || true)" >&2
  fail "$label expected one of {$*}, got $actual"
}

assert_code() {
  local expected="$1" label="$2"
  if grep -q "\"code\":\"$expected\"" /tmp/chaos-body.json; then
    pass "$label ($expected)"
  else
    printf '  body: %s\n' "$(cat /tmp/chaos-body.json 2>/dev/null || true)" >&2
    fail "$label expected code $expected"
  fi
}

wait_for_liveness() {
  for _ in $(seq 1 90); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health/liveness")" == "200" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "liveness did not become 200 within 90s"
}

wait_for_ok() {
  local path="$1" timeout="${2:-90}"
  for _ in $(seq 1 "$timeout"); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' -H "X-API-Key: $API_KEY" "$BASE_URL$path")" == "200" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "$path did not return 200 within ${timeout}s"
}

wait_for_not_ok() {
  local path="$1" timeout="${2:-90}"
  for _ in $(seq 1 "$timeout"); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL$path")" != "200" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "$path stayed 200 for ${timeout}s"
}

wait_for_status() {
  local path="$1" expected="$2" timeout="${3:-90}"
  for _ in $(seq 1 "$timeout"); do
    if [[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL$path")" == "$expected" ]]; then
      return 0
    fi
    sleep 1
  done
  fail "$path did not return $expected within ${timeout}s"
}

restore_api() {
  "${COMPOSE[@]}" up -d --force-recreate api >/dev/null
  wait_for_liveness
}
