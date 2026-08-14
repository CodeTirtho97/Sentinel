#!/usr/bin/env bash
set -euo pipefail

# Runs one of measurements 2-5 and appends its output to the raw results file.
#
# The Makefile targets do the same thing, but `make` is not on the PATH in Git Bash on Windows,
# so this is the no-make path. Every measurement is logged as well as printed: a number that
# exists only in a terminal you later close is a number you have to measure again.
#
#   ./scripts/measure.sh seed              # two SLOs per synthetic service — run this FIRST
#   ./scripts/measure.sh storm             # measurements 2 and 3
#   ./scripts/measure.sh replay            # measurement 5
#   ./scripts/measure.sh recovery          # measurement 4
#
#   FRACTION=0.3 ./scripts/measure.sh storm
#   COUNT=10000  ./scripts/measure.sh replay

# Git Bash (MSYS) rewrites anything that looks like a POSIX path before handing it to a native
# Windows binary, which mangles docker's -v flag: "/scripts" becomes "C:/Program Files/Git/scripts"
# and k6 starts with an empty mount. Harmless no-op on Linux and macOS.
export MSYS_NO_PATHCONV=1

RAW="${RAW:-docs/LOAD_TEST_RESULTS.raw.md}"
FRACTION="${FRACTION:-0.3}"
COUNT="${COUNT:-10000}"

# Which service the replay targets. Override to pick one that is NOT already breaching: the test
# asserts 10,000 duplicates create exactly ONE incident, and a service that already has an incident
# open attaches to it instead — reporting zero created, and failing for the wrong reason.
SERVICE="${SERVICE:-synth-c000-s4}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"

K6=(docker run --rm --network sentinel_default
    -v "$(pwd)/loadtest/k6:/scripts:ro"
    -e SENTINEL=http://sentinel:8080
    -e EXPORTER=http://synthetic-exporter:8080
    grafana/k6:0.52.0)

usage() {
  echo "usage: $0 {seed|storm|replay|recovery}" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage

mkdir -p "$(dirname "${RAW}")"

# Every measurement below assumes SLOs exist. Without them the evaluator has nothing to evaluate
# and each one reports a confidently wrong number rather than failing.
require_slos() {
  local count
  count=$(curl -fsS -H "X-Api-Key: ${API_KEY}" "http://localhost:3000/api/v1/slos" \
    | grep -o '"serviceName"' | wc -l | tr -d ' ')
  if [[ "${count}" -eq 0 ]]; then
    echo "No SLOs exist. Run './scripts/measure.sh seed' first — every measurement" >&2
    echo "after this point would otherwise report a number for doing nothing." >&2
    exit 1
  fi
  echo "  SLOs present: ${count}"
}

log_header() {
  printf '\n## %s — %s\n\n```\n' "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "${RAW}"
}

log_footer() {
  printf '```\n' >> "${RAW}"
  echo ""
  echo "  appended to ${RAW}"
}

case "$1" in
  seed)
    echo "==== SEEDING SLOs ===="
    "${K6[@]}" run --quiet /scripts/seed-synthetic-slos.js
    require_slos
    echo "  next: ./scripts/measure.sh storm"
    ;;

  storm)
    echo "==== MEASUREMENTS 2 + 3 — breach storm, alert collapse ===="
    require_slos
    echo "  fraction breaching: ${FRACTION}"
    echo "  expect ~10-18 min: 90s settle, then polling until the incident count holds for 60s."
    echo ""
    log_header "Breach storm + alert collapse (FRACTION=${FRACTION})"
    "${K6[@]}" run --quiet -e FRACTION="${FRACTION}" /scripts/breach-storm.js 2>&1 | tee -a "${RAW}"
    log_footer
    ;;

  replay)
    echo "==== MEASUREMENT 5 — duplicate replay ===="
    require_slos
    echo "  events: ${COUNT}   target: ${SERVICE}   expect ~3-8 min"
    echo "  a 404 from the replay endpoint means the loadtest profile is not active."
    echo ""
    log_header "Duplicate replay (COUNT=${COUNT}, SERVICE=${SERVICE})"
    "${K6[@]}" run --quiet -e COUNT="${COUNT}" -e SERVICE="${SERVICE}" /scripts/duplicate-replay.js 2>&1 | tee -a "${RAW}"
    log_footer
    ;;

  recovery)
    echo "==== MEASUREMENT 4 — recovery ===="
    require_slos
    echo ""
    log_header "Recovery"
    ./scripts/recovery-test.sh 2>&1 | tee -a "${RAW}"
    log_footer
    ;;

  *)
    usage
    ;;
esac
