#!/usr/bin/env bash
# Runs every local failure-injection check in sequence and reports a summary.
# Destructive only to the local Compose stack; it restores the API at the end of each scenario.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ "${CONFIRM_CHAOS:-}" != "yes" ]]; then
  printf 'WARNING: this stops/restarts local Docker dependencies and exercises failure paths.\n'
  printf 'It only affects the local Compose stack and restores the API afterwards.\n'
  if [[ ! -t 0 ]]; then
    printf 'Non-interactive shell: set CONFIRM_CHAOS=yes to run.\n' >&2
    exit 1
  fi
  read -r -p 'Type CHAOS to continue: ' answer
  [[ "$answer" == "CHAOS" ]] || { echo 'Aborted.'; exit 1; }
fi
export CONFIRM_CHAOS=yes
failures=0

for scenario in postgres-down broker-full bigquery-unavailable; do
  if ! bash "$ROOT/ops/chaos/$scenario.sh"; then
    failures=$((failures + 1))
    echo "  RESULT: $scenario FAILED" >&2
  fi
done

echo
if [[ "$failures" -eq 0 ]]; then
  echo "All failure-injection scenarios passed."
else
  echo "$failures failure-injection scenario(s) failed." >&2
  exit 1
fi
