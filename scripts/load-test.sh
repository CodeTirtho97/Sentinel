#!/usr/bin/env bash
set -euo pipefail

# Runs the evaluation-throughput ramp: measurement 1, at every fleet size.
#
# Each step is a full restart of the exporter and a clean Prometheus, because a run that inherits
# the previous size's series measures both at once. Output goes to docs/LOAD_TEST_RESULTS.raw.md
# for pasting into the results table.
#
# Three sizes, twenty minutes each, because the shape of the curve is the finding and a p99 over
# 40 cycles is not a p99. The ramp tops out at 500 because of the 8GB Docker VM it runs in, not
# because the evaluator ran out of headroom — since query count is constant in N, a larger top end
# would extend the flat line rather than terminate it. The reasoning is in
# docs/BENCHMARK_METHODOLOGY.md §3.
#
#   ./scripts/load-test.sh                 # 100 250 500, 20 min each
#   SIZES="100" DURATION_MIN=3 ./scripts/load-test.sh    # the smoke run — do this first

# Git Bash (MSYS) rewrites anything that looks like a POSIX path before handing it to a native
# Windows binary, which mangles docker's -v flag: "/scripts" becomes "C:/Program Files/Git/scripts"
# and the container starts with an empty mount. Harmless no-op on Linux and macOS.
export MSYS_NO_PATHCONV=1

SIZES="${SIZES:-100 250 500}"
DURATION_MIN="${DURATION_MIN:-20}"
OUT="${OUT:-docs/LOAD_TEST_RESULTS.raw.md}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"
# Bounded, so a stack that never comes up fails in five minutes with a hint rather than hanging
# silently overnight — which is the whole point of running this unattended.
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300}"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.loadtest.yml)
LOADTEST_SERVICES=(postgres redpanda redis prometheus grafana synthetic-exporter sentinel)

hms() { printf '%dm%02ds' $(($1 / 60)) $(($1 % 60)); }

# Each step is: teardown + build/start (~90s) + settle (90s) + sample + seed. The estimate is
# deliberately rough; it exists so you know whether to wait or go and do something else.
step_estimate_min=$((DURATION_MIN + 5))
size_count=$(printf '%s\n' ${SIZES} | wc -l | tr -d ' ')
run_started=${SECONDS}

wait_for() {
  local name="$1" url="$2" deadline=$((SECONDS + WAIT_TIMEOUT))
  printf '    %-20s' "${name}"
  until curl -fsS "${url}" >/dev/null 2>&1; do
    if (( SECONDS > deadline )); then
      printf ' TIMEOUT after %ss\n' "${WAIT_TIMEOUT}"
      echo "" >&2
      echo "    ${name} never became reachable at ${url}" >&2
      echo "    try: ${COMPOSE[*]} logs --tail=50 ${name}" >&2
      exit 1
    fi
    printf '.'
    sleep 3
  done
  printf ' ok\n'
}

mkdir -p "$(dirname "${OUT}")"
{
  echo "# Raw load test output"
  echo ""
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Sizes: ${SIZES}    Duration per step: ${DURATION_MIN} min"
  echo ""
} > "${OUT}"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  MEASUREMENT 1 — evaluation throughput"
echo "════════════════════════════════════════════════════════════"
echo "  Sizes:          ${SIZES}  (${size_count} steps)"
echo "  Sampling:       ${DURATION_MIN} min per size, after a 90s settle"
echo "  Est. total:     ~$((step_estimate_min * size_count)) min"
echo "  Raw output:     ${OUT}"
echo "  Progress is printed every 60s during sampling — silence means something is wrong."
echo ""

step=0
for size in ${SIZES}; do
  step=$((step + 1))
  step_started=${SECONDS}

  echo ""
  echo "────────────────────────────────────────────────────────────"
  echo "  STEP ${step}/${size_count} — ${size} synthetic services ($((size * 2)) SLOs)"
  echo "  elapsed so far: $(hms $((SECONDS - run_started)))   est. remaining: ~$(( (size_count - step + 1) * step_estimate_min )) min"
  echo "────────────────────────────────────────────────────────────"

  # Volumes go too: Prometheus carrying the previous size's series would leave the evaluator
  # querying stale services that no longer exist, and the cycle would be timing the wrong fleet.
  echo "  [1/5] tearing down previous stack (volumes included)..."
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true

  echo "  [2/5] starting stack with SYNTHETIC_SERVICES=${size}..."
  SYNTHETIC_SERVICES="${size}" "${COMPOSE[@]}" up -d --build "${LOADTEST_SERVICES[@]}"

  echo "  [3/5] waiting for health..."
  wait_for sentinel "http://localhost:3000/actuator/health"
  wait_for exporter "http://localhost:8089/status"
  wait_for prometheus "http://localhost:9090/-/healthy"

  echo "  [4/5] seeding $((size * 2)) SLOs..."
  docker run --rm --network sentinel_default -v "$(pwd)/loadtest/k6:/scripts:ro" \
    -e SENTINEL=http://sentinel:8080 -e EXPORTER=http://synthetic-exporter:8080 \
    grafana/k6:0.52.0 run --quiet /scripts/seed-synthetic-slos.js

  # An unseeded run reports a wonderfully fast p99 for doing nothing at all. Catch it here rather
  # than in the results table twenty minutes later.
  seeded=$(curl -fsS -H "X-Api-Key: ${API_KEY}" "http://localhost:3000/api/v1/slos" \
    | grep -o '"serviceName"' | wc -l | tr -d ' ')
  echo "        SLOs now present: ${seeded} (expected $((size * 2)))"
  if [[ "${seeded}" -eq 0 ]]; then
    echo "  seeding produced no SLOs — everything after this would measure an empty cycle" >&2
    exit 1
  fi
  if [[ "${seeded}" -ne $((size * 2)) ]]; then
    echo "        WARNING: count does not match; the row below is suspect" >&2
  fi

  echo "  [5/5] measuring for ${DURATION_MIN} min (+90s settle)..."
  {
    echo ""
    echo "## ${size} services ($((size * 2)) SLOs)"
    echo ""
    echo "SLOs confirmed present before sampling: ${seeded}"
    echo ""
    echo '```'
  } >> "${OUT}"

  docker run --rm --network sentinel_default -v "$(pwd)/loadtest/k6:/scripts:ro" \
    -e SENTINEL=http://sentinel:8080 -e DURATION_MIN="${DURATION_MIN}" \
    grafana/k6:0.52.0 run --quiet /scripts/evaluation-throughput.js 2>&1 | tee -a "${OUT}"

  echo '```' >> "${OUT}"
  echo ""
  echo "  step ${step}/${size_count} done in $(hms $((SECONDS - step_started)))"
done

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  RAMP COMPLETE — total $(hms $((SECONDS - run_started)))"
echo "════════════════════════════════════════════════════════════"
echo "  Raw output: ${OUT}"
echo ""
echo "  Check each block for:"
echo "    SLOs evaluated:    equals 2x the service count"
echo "    Cycles completed:  close to expected"
echo "    Cycle p50/p95/p99: a millisecond figure, not 'no data'"
echo "    Query failures:    0"
echo ""
echo "  Then transcribe into docs/LOAD_TEST_RESULTS.md with the hardware spec."
echo "  The stack is still running; 'make load-test-down' when finished."
