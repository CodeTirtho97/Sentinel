#!/usr/bin/env bash
set -euo pipefail

# Breaks ledger-service, the leaf of the order path.
#
#   api-gateway -> checkout -> {cart, payment}; cart -> payment; payment -> fraud -> ledger
#
# Every call is synchronous, so the failure climbs all the way to the front door and six services
# breach. Sentinel must turn that into ONE incident naming ledger-service as the origin.
#
# The added latency must exceed the 500ms latency-SLO threshold at ledger itself, not merely by the
# time it has accumulated up the chain. At 400ms ledger stays inside its own objective while the
# entry point — whose latency is the sum of the whole chain — breaches a full cycle earlier than
# anything else. Origin inference then correctly reports the earliest breach and names the front
# door, the symptom, as the origin of a failure that started at the leaf.
#
# At 600ms ledger breaches its own latency objective in the same cycle as its callers, the detection
# times tie, and the tie is broken on depth in the call graph — which names ledger.

source "$(dirname "$0")/fleet.sh"

ERROR_RATE="${ERROR_RATE:-0.35}"
LATENCY_MS="${LATENCY_MS:-600}"
LEDGER="http://localhost:$(fleet_port_of ledger-service)"

echo "Injecting failure into ledger-service:"
echo "  error rate    ${ERROR_RATE}"
echo "  added latency ${LATENCY_MS}ms"

curl -fsS -X POST "$LEDGER/chaos/errors?rate=${ERROR_RATE}" >/dev/null
curl -fsS -X POST "$LEDGER/chaos/latency?ms=${LATENCY_MS}" >/dev/null

cat <<'EOF'

Expect, within about a minute:
  ONE incident, origin ledger-service, six affected services
  (ledger, fraud, payment, cart, checkout, api-gateway)

Watch it happen:
  ./scripts/watch-incidents.sh
  http://localhost:3000

Reset with: ./scripts/reset-chaos.sh
EOF
