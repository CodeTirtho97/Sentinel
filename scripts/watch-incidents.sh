#!/usr/bin/env bash
set -uo pipefail

# Live play-by-play of what Sentinel is doing, with elapsed times.
#
# The reaction is otherwise only visible to someone who knows which endpoint to curl. This prints
# the story as it happens: incident opened, services joining the blast radius, severity changing,
# state transitions, auto-resolution — so the pipeline explains itself on a shared screen.
#
# Read-only. Safe to leave running for the whole demo.

SENTINEL="${SENTINEL:-http://localhost:3000}"
# /api/v1 is behind a static API key. The Compose stack ships with the development default.
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"
INTERVAL="${INTERVAL:-2}"

if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
  echo "needs python (for JSON parsing)" >&2
  exit 1
fi
PY="$(command -v python3 || command -v python)"

BOLD=$'\033[1m'; DIM=$'\033[2m'; RED=$'\033[31m'; GREEN=$'\033[32m'
YELLOW=$'\033[33m'; CYAN=$'\033[36m'; RESET=$'\033[0m'

START=$SECONDS
declare -A SEEN_STATE      # incident id -> last state
declare -A SEEN_SERVICES   # incident id -> comma-joined affected services
declare -A SEEN_SEVERITY   # incident id -> last severity
declare -A SHORT_ID        # incident id -> #1, #2, ...
NEXT_NUM=1

stamp() { printf '%s[t+%3ds]%s ' "$DIM" "$((SECONDS - START))" "$RESET"; }

banner() {
  echo "${BOLD}Sentinel — live incident feed${RESET}   ${DIM}polling ${SENTINEL} every ${INTERVAL}s${RESET}"
  echo "${DIM}$(printf '%.0s─' {1..78})${RESET}"
  stamp; echo "watching. break something with ./scripts/demo.sh break"
}

banner

while true; do
  # One line per incident: id|state|severity|origin|breachCount|svc,svc,svc
  snapshot="$("$PY" - "$SENTINEL" "$API_KEY" <<'PYEOF' 2>/dev/null
import json, sys, urllib.request
try:
    request = urllib.request.Request(
        sys.argv[1] + "/api/v1/incidents?size=50", headers={"X-Api-Key": sys.argv[2]})
    with urllib.request.urlopen(request, timeout=5) as r:
        for i in json.load(r):
            print("|".join([
                i["id"], i["state"], i["severity"], i.get("originService") or "?",
                str(i["breachCount"]), ",".join(sorted(i["affectedServices"])),
            ]))
except Exception:
    sys.exit(1)
PYEOF
)" || { sleep "$INTERVAL"; continue; }

  while IFS='|' read -r id state severity origin breaches services; do
    [ -z "${id:-}" ] && continue

    if [ -z "${SHORT_ID[$id]:-}" ]; then
      SHORT_ID[$id]="#${NEXT_NUM}"; NEXT_NUM=$((NEXT_NUM + 1))
      count=$(awk -F, '{print NF}' <<<"$services")
      stamp
      echo "${RED}${BOLD}INCIDENT ${SHORT_ID[$id]} OPENED${RESET}  ${BOLD}${severity}${RESET}"
      printf '          origin  %s%s%s\n' "$YELLOW" "$origin" "$RESET"
      printf '          blast   %s service(s): %s\n' "$count" "$services"
      SEEN_STATE[$id]="$state"; SEEN_SERVICES[$id]="$services"; SEEN_SEVERITY[$id]="$severity"
      continue
    fi

    # Blast radius grew — the cascade is still climbing the call chain.
    if [ "${SEEN_SERVICES[$id]}" != "$services" ]; then
      before="${SEEN_SERVICES[$id]}"
      joined="$(tr ',' '\n' <<<"$services" | grep -vxF -f <(tr ',' '\n' <<<"$before") | paste -sd, -)"
      count=$(awk -F, '{print NF}' <<<"$services")
      stamp
      echo "${CYAN}JOINED${RESET} ${SHORT_ID[$id]}  ${BOLD}${joined}${RESET}  ${DIM}(blast radius now ${count})${RESET}"
      SEEN_SERVICES[$id]="$services"
    fi

    if [ "${SEEN_SEVERITY[$id]}" != "$severity" ]; then
      stamp; echo "${YELLOW}SEVERITY${RESET} ${SHORT_ID[$id]}  ${SEEN_SEVERITY[$id]} -> ${BOLD}${severity}${RESET}"
      SEEN_SEVERITY[$id]="$severity"
    fi

    if [ "${SEEN_STATE[$id]}" != "$state" ]; then
      colour="$YELLOW"; [ "$state" = "RESOLVED" ] && colour="$GREEN"
      stamp
      echo "${colour}${state}${RESET} ${SHORT_ID[$id]}  ${DIM}(was ${SEEN_STATE[$id]}, ${breaches} breaches absorbed)${RESET}"
      SEEN_STATE[$id]="$state"
    fi
  done <<<"$snapshot"

  # The headline number: raw alerts a naive system would have paged for, versus incidents raised.
  open_count=0; total_breaches=0
  while IFS='|' read -r id state severity origin breaches services; do
    [ -z "${id:-}" ] && continue
    total_breaches=$((total_breaches + breaches))
    [ "$state" != "RESOLVED" ] && open_count=$((open_count + 1))
  done <<<"$snapshot"

  if [ "$total_breaches" -gt 0 ] && [ "$open_count" -gt 0 ]; then
    printf '\r%s  %s breaches absorbed into %s open incident(s)%s' \
      "$DIM" "$total_breaches" "$open_count" "$RESET"
  fi

  sleep "$INTERVAL"
done
