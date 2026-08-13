#!/usr/bin/env bash
set -euo pipefail

# Clears failure injection on every fleet instance.

source "$(dirname "$0")/fleet.sh"

for entry in "${FLEET[@]}"; do
  service="${entry%%:*}"
  port="${entry##*:}"
  printf '  resetting %-18s (%s) ... ' "$service" "$port"
  curl -fsS -X POST "http://localhost:${port}/chaos/reset" >/dev/null && echo "ok"
done

echo
echo "All chaos cleared."
echo "Burn rates decay over the length of the long window, so breaches keep firing for a few more"
echo "minutes before the quiet period that auto-resolves the incident can start counting."
