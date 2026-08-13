#!/usr/bin/env bash
set -euo pipefail

# Breaks BOTH trees at once, to show what correlation refuses to do.
#
#   ORDER PATH   api-gateway -> checkout -> {cart, payment}; cart -> payment;
#                payment -> fraud -> ledger          <- broken here
#   BROWSE PATH  search -> catalog                   <- and here
#
# Eight services breaching simultaneously, but two unrelated causes. A system that simply groups
# whatever is broken right now would report one incident spanning the whole fleet, which is a
# confident, useless answer. The subgraph is induced by the breaching services only, and no edge
# joins the two trees, so they stay two components and therefore two incidents.
#
# This is the negative case, and it is the half of the proof that a single-cascade demo cannot show.

source "$(dirname "$0")/fleet.sh"

ERROR_RATE="${ERROR_RATE:-0.35}"
LATENCY_MS="${LATENCY_MS:-600}"

break_service() {
  local service="$1" port
  port="$(fleet_port_of "$service")"
  printf '  breaking %-18s (errors %s, +%sms) ... ' "$service" "$ERROR_RATE" "$LATENCY_MS"
  curl -fsS -X POST "http://localhost:${port}/chaos/errors?rate=${ERROR_RATE}" >/dev/null
  curl -fsS -X POST "http://localhost:${port}/chaos/latency?ms=${LATENCY_MS}" >/dev/null
  echo "ok"
}

echo "Injecting two unrelated failures:"
break_service ledger-service
break_service catalog-service

cat <<'EOF'

Expect, within about a minute:
  TWO incidents, not one and not eight

  #1  origin ledger-service   six affected  (order path)
  #2  origin catalog-service  two affected  (browse path)

The point is the boundary between them. Correlation groups what is connected, not what is merely
broken at the same moment.

Watch it happen:
  ./scripts/watch-incidents.sh
  http://localhost:3000

Reset with: ./scripts/reset-chaos.sh
EOF
