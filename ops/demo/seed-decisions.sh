#!/usr/bin/env bash
# Seed one durable decision arc through the public API so a read-only reviewer sees runs, proposals,
# and a purchase order immediately. Requires the *operator* key; the read key cannot write.
#
# Usage:
#   BASE_URL=https://... ADMIN_API_KEY=... ./ops/demo/seed-decisions.sh
#   BASE_URL=... ADMIN_API_KEY=... STORE_ID=ST-001 SUPPLIER_ID=SUP-001 ./ops/demo/seed-decisions.sh
set -euo pipefail

if [[ "${CONFIRM_SEED:-}" != "yes" ]]; then
  printf 'WARNING: this creates a durable replenishment run, approves a proposal, and creates/submits a purchase order.\n'
  printf 'It writes demo data through the public API; it does not delete anything.\n'
  if [[ ! -t 0 ]]; then
    printf 'Non-interactive shell: set CONFIRM_SEED=yes to run.\n' >&2
    exit 1
  fi
  read -r -p "Type CREATE to continue: " answer
  [[ "$answer" == "CREATE" ]] || { echo "Aborted."; exit 1; }
fi

BASE_URL="${BASE_URL:?set BASE_URL}"
ADMIN_API_KEY="${ADMIN_API_KEY:?set ADMIN_API_KEY}"
STORE_ID="${STORE_ID:-ST-001}"
SUPPLIER_ID="${SUPPLIER_ID:-}"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -sS --fail-with-body -X "$method" \
      -H "X-API-Key: $ADMIN_API_KEY" -H 'Content-Type: application/json' \
      -d "$body" "$BASE_URL$path"
  else
    curl -sS --fail-with-body -X "$method" \
      -H "X-API-Key: $ADMIN_API_KEY" "$BASE_URL$path"
  fi
}

say() { printf '%s\n' "$*"; }

say "Creating a replenishment run for $STORE_ID ..."
RUN_JSON="$(api POST /api/v1/retail/replenishment-runs "{\"storeId\":\"$STORE_ID\"}")"
RUN_ID="$(jq -r '.runId' <<<"$RUN_JSON")"
STATUS="$(jq -r '.status' <<<"$RUN_JSON")"
say "runId=$RUN_ID status=$STATUS"

PROPOSALS="$(api GET "/api/v1/retail/replenishment-runs/$RUN_ID/proposals?limit=1")"
PRODUCT_ID="$(jq -r '.items[0].productId // empty' <<<"$PROPOSALS")"
if [[ -z "$PRODUCT_ID" ]]; then
  say "No proposals were produced. Is the dataset seeded and is there demand for $STORE_ID?"
  exit 1
fi
VERSION="$(jq -r '.items[0].version' <<<"$PROPOSALS")"
PROPOSED_SUPPLIER="$(jq -r '.items[0].supplierId' <<<"$PROPOSALS")"
say "Approving $PRODUCT_ID (version $VERSION) ..."
api POST "/api/v1/retail/replenishment-runs/$RUN_ID/proposals/$PRODUCT_ID/approve" \
  "{\"expectedVersion\":$VERSION,\"note\":\"showcase seed\"}" >/dev/null

SUPPLIER_ID="${SUPPLIER_ID:-${PROPOSED_SUPPLIER:-SUP-001}}"
say "Creating a draft purchase order for supplier $SUPPLIER_ID ..."
PO_JSON="$(api POST /api/v1/retail/purchase-orders \
  "{\"runId\":\"$RUN_ID\",\"storeId\":\"$STORE_ID\",\"supplierId\":\"$SUPPLIER_ID\"}")"
PO_ID="$(jq -r '.poId' <<<"$PO_JSON")"
PO_VERSION="$(jq -r '.version' <<<"$PO_JSON")"
say "poId=$PO_ID version=$PO_VERSION"

say "Submitting the purchase order ..."
api POST "/api/v1/retail/purchase-orders/$PO_ID/submit" \
  "{\"expectedVersion\":$PO_VERSION,\"supplierReference\":\"SHOWCASE-1\"}" >/dev/null

say "Enqueuing supplier transmission ..."
api POST "/api/v1/retail/purchase-orders/$PO_ID/transmit" >/dev/null

say "Done."
say "  run:         GET /api/v1/retail/replenishment-runs/$RUN_ID"
say "  proposals:   GET /api/v1/retail/replenishment-runs/$RUN_ID/proposals"
say "  purchase:    GET /api/v1/retail/purchase-orders/$PO_ID"
say "  outbox:      GET /api/v1/retail/supplier-transmissions"
say "Hand the reviewer the read-only key; it can read these but cannot change them."
