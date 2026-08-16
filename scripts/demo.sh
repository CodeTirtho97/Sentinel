#!/usr/bin/env bash
set -euo pipefail

# Terminal control for the demo — the same actions as the buttons on http://localhost:3000.
#
# This is deliberately a thin wrapper over /api/v1/demo/*. It knows no SLO objectives, no fleet
# list, no chaos rates: DemoControlController owns all of that. The previous scripts reimplemented
# it by posting to /api/v1/slos and to each fleet container's /chaos endpoint directly, which meant
# the service list and the SLO parameters existed in two places and could disagree without anyone
# noticing until a demo behaved differently depending on how it was started.
#
# Starting and stopping the stack is not here on purpose. `docker compose` already does that in one
# cross-platform command and does not need wrapping:
#
#   docker compose up -d --wait     start everything, wait until healthy
#   docker compose down -v          stop and wipe all state
#   docker compose logs -f sentinel tail the platform

SENTINEL="${SENTINEL:-http://localhost:3000}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"

usage() {
    cat <<EOF
Usage: ./scripts/demo.sh <command>

  seed         create SLOs for all eight fleet services
  break        break ledger-service — one cascade, one incident
  break-both   break ledger-service and catalog-service — two incidents
  reset        clear all injected failure
  kill         halt Sentinel mid-incident; Docker restarts it, the incident survives
  status       fleet health and injected-chaos state

Environment:
  SENTINEL           default http://localhost:3000
  SENTINEL_API_KEY   default local-dev-key

Everything here is also a button at $SENTINEL.
EOF
}

# Demo endpoints exist only under the demo profile, which is the Compose default. A 404 therefore
# means the stack is running some other profile, and saying so beats echoing raw curl output.
api() {
    local method="$1" path="$2" status body
    body=$(curl -sS -X "$method" -H "X-Api-Key: $API_KEY" \
        -w '\n%{http_code}' "$SENTINEL$path" 2>&1) || {
        echo "  cannot reach $SENTINEL — is the stack up? (docker compose up -d --wait)" >&2
        return 1
    }
    status="${body##*$'\n'}"
    body="${body%$'\n'*}"

    case "$status" in
        2*) printf '%s\n' "$body" ;;
        401) echo "  401 — wrong API key. Set SENTINEL_API_KEY." >&2; return 1 ;;
        404) echo "  404 — demo endpoints are demo-profile only. Check SPRING_PROFILES_ACTIVE." >&2; return 1 ;;
        *)   echo "  $status: $body" >&2; return 1 ;;
    esac
}

case "${1:-}" in
    seed)       api POST /api/v1/demo/seed ;;
    break)      api POST '/api/v1/demo/chaos?services=ledger-service' ;;
    break-both) api POST '/api/v1/demo/chaos?services=ledger-service,catalog-service' ;;
    reset)      api POST /api/v1/demo/reset ;;
    status)     api GET  /api/v1/demo/status ;;
    # The process is halted rather than asked to stop, so the connection dies with it. A non-zero
    # curl exit here is the endpoint working, not failing.
    kill)       api POST /api/v1/demo/kill || echo "  Sentinel halted. Docker will restart it." ;;
    ""|-h|--help|help) usage ;;
    *)          echo "unknown command: $1" >&2; echo >&2; usage >&2; exit 1 ;;
esac
