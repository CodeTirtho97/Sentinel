#!/usr/bin/env bash
set -euo pipefail

# Waits for every stack component to report healthy before the demo continues.
#
# SKIP_FLEET=1 waits for the load-test stack instead: the overlay deliberately does not start the
# eight demo-fleet JVMs, so waiting on them would be eight consecutive timeouts and a failed run.
# It waits for the synthetic exporter in their place.

TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"
SKIP_FLEET="${SKIP_FLEET:-0}"

wait_for() {
  local name="$1" url="$2" deadline=$((SECONDS + TIMEOUT_SECONDS))
  printf '  waiting for %-18s' "$name"
  until curl -fsS "$url" >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then
      printf ' TIMEOUT\n'
      echo "  $name did not become healthy at $url" >&2
      echo "  try: docker compose logs $name" >&2
      return 1
    fi
    printf '.'
    sleep 2
  done
  printf ' ok\n'
}

if [[ "${SKIP_FLEET}" == "1" ]]; then
  echo "Waiting for the load-test stack (no demo fleet)..."
else
  echo "Waiting for the stack..."
fi
source "$(dirname "$0")/fleet.sh"

wait_for prometheus "http://localhost:9090/-/healthy"
wait_for grafana    "http://localhost:3001/api/health"

if [[ "${SKIP_FLEET}" == "1" ]]; then
  wait_for exporter "http://localhost:8089/status"
else
  # Leaves first: an entry point reports healthy long before the chain beneath it can serve a request.
  for (( idx=${#FLEET[@]}-1; idx>=0; idx-- )); do
    entry="${FLEET[$idx]}"
    wait_for "${entry%%:*}" "http://localhost:${entry##*:}/actuator/health"
  done
fi

wait_for sentinel "http://localhost:3000/actuator/health"
echo "Stack is up."
