#!/usr/bin/env bash
set -euo pipefail

# Runs the evaluation-throughput ramp: measurement 1, at every fleet size.
#
# Each step is a full restart of the exporter and a clean Prometheus, because a run that inherits
# the previous size's series measures both at once. Output goes to docs/LOAD_TEST_RESULTS.raw.md
# for pasting into the results table.
#
#   ./scripts/load-test.sh                 # 100 250 500 1000 2000
#   SIZES="100 500" DURATION_MIN=5 ./scripts/load-test.sh

SIZES="${SIZES:-100 250 500 1000 2000}"
DURATION_MIN="${DURATION_MIN:-10}"
OUT="${OUT:-docs/LOAD_TEST_RESULTS.raw.md}"

COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.loadtest.yml)
LOADTEST_SERVICES=(postgres redpanda redis prometheus grafana synthetic-exporter sentinel)

mkdir -p "$(dirname "${OUT}")"
{
  echo "# Raw load test output"
  echo ""
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Duration per step: ${DURATION_MIN} min"
  echo ""
} > "${OUT}"

for size in ${SIZES}; do
  echo ""
  echo "############################################################"
  echo "#  ${size} synthetic services"
  echo "############################################################"

  # Volumes go too: Prometheus carrying the previous size's series would leave the evaluator
  # querying stale services that no longer exist, and the cycle would be timing the wrong fleet.
  "${COMPOSE[@]}" down -v >/dev/null 2>&1 || true

  SYNTHETIC_SERVICES="${size}" "${COMPOSE[@]}" up -d --build "${LOADTEST_SERVICES[@]}"

  echo "  waiting for sentinel..."
  until curl -fsS http://localhost:3000/actuator/health >/dev/null 2>&1; do sleep 3; done
  until curl -fsS http://localhost:8089/status >/dev/null 2>&1; do sleep 3; done

  echo "  seeding $((size * 2)) SLOs..."
  docker run --rm --network sentinel_default -v "$(pwd)/loadtest/k6:/scripts:ro" \
    -e SENTINEL=http://sentinel:8080 -e EXPORTER=http://synthetic-exporter:8080 \
    grafana/k6:0.52.0 run /scripts/seed-synthetic-slos.js

  echo "  measuring..."
  {
    echo ""
    echo "## ${size} services ($((size * 2)) SLOs)"
    echo ""
    echo '```'
  } >> "${OUT}"

  docker run --rm --network sentinel_default -v "$(pwd)/loadtest/k6:/scripts:ro" \
    -e SENTINEL=http://sentinel:8080 -e DURATION_MIN="${DURATION_MIN}" \
    grafana/k6:0.52.0 run /scripts/evaluation-throughput.js 2>&1 | tee -a "${OUT}"

  echo '```' >> "${OUT}"
done

echo ""
echo "Raw output written to ${OUT}"
echo "Transcribe the numbers into docs/LOAD_TEST_RESULTS.md, including the hardware spec."
