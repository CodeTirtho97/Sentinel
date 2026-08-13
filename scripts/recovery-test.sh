#!/usr/bin/env bash
set -euo pipefail

# Measurement 4: durability, with a number.
#
# Kills sentinel-platform mid-cycle and measures how long it takes to resume normal evaluation,
# then asserts nothing was duplicated on the way back. This is the measurement behind the 1:40 beat
# of the demo script, and the reason manual ack plus a partial unique index earn their complexity.

SENTINEL_URL="${SENTINEL_URL:-http://localhost:3000}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

api() { curl -fsS -H "X-Api-Key: ${API_KEY}" "$@"; }

cycles() {
  curl -fsS "${SENTINEL_URL}/actuator/prometheus" \
    | awk '/^sentinel_slo_cycle_duration_seconds_count/ {print $2; exit}'
}

incident_count() { api "${SENTINEL_URL}/api/v1/incidents?size=200" | grep -o '"id"' | wc -l | tr -d ' '; }

# A duplicate is two unresolved incidents sharing a correlation key — precisely what the partial
# unique index exists to make impossible.
duplicate_keys() {
  api "${SENTINEL_URL}/api/v1/incidents?size=200&state=OPEN" \
    | grep -o '"correlationKey":"[^"]*"' | sort | uniq -d | wc -l | tr -d ' '
}

echo "==== RECOVERY TEST ===="

before_incidents=$(incident_count)
before_dupes=$(duplicate_keys)
echo "  incidents before kill:   ${before_incidents}"
echo "  duplicate keys before:   ${before_dupes}"

echo "  killing sentinel mid-cycle..."
docker kill sentinel-sentinel-1 >/dev/null 2>&1 || docker compose kill sentinel >/dev/null
killed_at=$(date +%s)

echo "  restarting..."
docker compose start sentinel >/dev/null 2>&1 || docker compose up -d sentinel >/dev/null

# Healthy is not the same as working. What matters is that the evaluator has completed a cycle
# since coming back, so wait for the cycle counter to actually advance.
printf '  waiting for the first completed cycle'
deadline=$((SECONDS + TIMEOUT_SECONDS))
first_cycles=""
until [[ -n "${first_cycles}" ]]; do
  if (( SECONDS > deadline )); then
    printf ' TIMEOUT\n'
    echo "  sentinel did not resume evaluating within ${TIMEOUT_SECONDS}s" >&2
    exit 1
  fi
  printf '.'
  sleep 2
  first_cycles=$(cycles 2>/dev/null || true)
done

baseline="${first_cycles}"
until [[ "$(cycles 2>/dev/null || echo "${baseline}")" != "${baseline}" ]]; do
  if (( SECONDS > deadline )); then
    printf ' TIMEOUT\n'
    echo "  sentinel came up but never completed a second cycle" >&2
    exit 1
  fi
  printf '.'
  sleep 2
done
printf ' ok\n'

recovered_at=$(date +%s)
recovery_seconds=$((recovered_at - killed_at))

# Give the consumers a moment to redeliver anything uncommitted before counting.
sleep 20

after_incidents=$(incident_count)
after_dupes=$(duplicate_keys)

echo ""
echo "  Kill-to-steady-state:    ${recovery_seconds}s"
echo "  Incidents before:        ${before_incidents}"
echo "  Incidents after:         ${after_incidents}"
echo "  Duplicate active keys:   ${after_dupes}   (expected 0)"
echo "======================="

if [[ "${after_dupes}" -ne 0 ]]; then
  echo "FAIL: ${after_dupes} correlation keys have more than one active incident" >&2
  exit 1
fi
echo "PASS: no duplicate incidents across the restart"
