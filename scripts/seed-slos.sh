#!/usr/bin/env bash
set -euo pipefail

# Creates one availability and one latency SLO per fleet service.
# Re-running is safe: a duplicate target returns 409 and is skipped.

SENTINEL="${SENTINEL:-http://localhost:3000}"
# /api/v1 is behind a static API key. The Compose stack ships with the development default.
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"

source "$(dirname "$0")/fleet.sh"
mapfile -t SERVICES < <(fleet_services)

create_slo() {
  local body="$1"
  local status
  status=$(curl -s -o /tmp/sentinel-seed-response -w '%{http_code}' \
    -X POST "$SENTINEL/api/v1/slos" \
    -H "X-Api-Key: $API_KEY" \
    -H 'Content-Type: application/json' \
    -d "$body")

  case "$status" in
    201) echo "  created" ;;
    409) echo "  already exists" ;;
    *)   echo "  FAILED ($status): $(cat /tmp/sentinel-seed-response)" >&2; return 1 ;;
  esac
}

echo "Seeding SLOs..."
for service in "${SERVICES[@]}"; do
  printf '  %-18s availability 99.9%%' "$service"
  create_slo "{\"serviceName\":\"$service\",\"type\":\"AVAILABILITY\",\"objective\":0.999,\"rollingWindow\":\"P30D\"}"

  printf '  %-18s latency 500ms 99%%' "$service"
  create_slo "{\"serviceName\":\"$service\",\"type\":\"LATENCY\",\"objective\":0.99,\"latencyThresholdMs\":500,\"rollingWindow\":\"P30D\"}"
done

echo "Seeded. Current SLOs:"
curl -fsS -H "X-Api-Key: $API_KEY" "$SENTINEL/api/v1/slos" | head -c 2000
echo
