#!/usr/bin/env bash
set -euo pipefail

# Measurements 2 and 3, as a RAMP rather than a single shot.
#
# Flips an increasing fraction of synthetic chains into breach — 10%, 20%, 30%, 40%, 50% — and after
# each step records detection latency, the alert-collapse ratio, and what the storm costs in
# resources. A single 30% run tells you whether 30% works. A ramp tells you the shape, and where the
# knee is if there is one.
#
# 50% is the deliberate ceiling. Past half the fleet failing you are no longer measuring a storm,
# you are measuring a different steady state in which the breach path IS the normal path — which is
# a fair thing to want to know, but it is not what this measurement is for.
#
#   ./scripts/breach-ramp.sh
#   FRACTIONS="0.1 0.3 0.5" ./scripts/breach-ramp.sh
#
# IMPORTANT — the fractions are CUMULATIVE by construction. The exporter breaches chains 0..N, so
# raising the fraction widens the existing storm rather than starting a new one. Chains already
# breaching stay breaching and stay attached to their existing incidents; only the newly added
# chains can open new ones. Every row below is therefore reported as a DELTA against the previous
# step, with cumulative totals alongside.

FRACTIONS="${FRACTIONS:-0.1 0.2 0.3 0.4 0.5}"
SENTINEL_URL="${SENTINEL_URL:-http://localhost:3000}"
EXPORTER_URL="${EXPORTER_URL:-http://localhost:8089}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"
OUT="${OUT:-docs/LOAD_TEST_RESULTS.raw-storm.md}"

SETTLE_SECONDS="${SETTLE_SECONDS:-90}"   # once, before the first step
STABLE_SECONDS="${STABLE_SECONDS:-60}"   # incident count must hold this long
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-480}"
POLL_SECONDS=5

# Abort thresholds. The point of a stress ramp is to find where the pipeline gives out, and once it
# has given out every further step burns twenty minutes measuring the same saturated system. These
# stop the run at the knee and report it, rather than hanging on to 50% for the sake of the label.
LAG_ABORT="${LAG_ABORT:-60000}"          # breach-topic backlog the consumer will not recover from
CYCLE_ABORT_MS="${CYCLE_ABORT_MS:-10000}" # 2/3 of the 15s interval: the evaluator itself is in trouble
MEM_ABORT_PCT="${MEM_ABORT_PCT:-85}"     # of the Docker VM; past this the run measures swap
abort_reason=""

scrape() { curl -fsS "${SENTINEL_URL}/actuator/prometheus" 2>/dev/null || true; }

# Sum every line of a metric, optionally filtered to a label substring. Counters are per-partition
# or per-severity, so reading the first line only would report a fraction as the whole.
sum_metric() {
  local body="$1" name="$2" filter="${3:-}"
  printf '%s\n' "${body}" | python -c "
import sys
name, filt = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else '')
total, found = 0.0, False
for line in sys.stdin:
    if line.startswith('#') or not line.startswith(name): continue
    if filt and filt not in line: continue
    try:
        total += float(line.rsplit(' ', 1)[1]); found = True
    except (ValueError, IndexError): pass
print(f'{total:.6f}' if found else '0')
" "$2" "${filter}"
}

max_metric() {
  local body="$1" name="$2" filter="${3:-}"
  printf '%s\n' "${body}" | python -c "
import sys
name, filt = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else '')
best = 0.0
for line in sys.stdin:
    if line.startswith('#') or not line.startswith(name): continue
    if filt and filt not in line: continue
    try: best = max(best, float(line.rsplit(' ', 1)[1]))
    except (ValueError, IndexError): pass
print(f'{best:.0f}')
" "$2" "${filter}"
}

# Incidents come from the MONOTONIC COUNTER, not the active gauge.
#
# sentinel_incidents_active is a gauge, and under sustained load it falls: consumer lag makes
# last_breach_at go stale, the auto-resolve sweep fires on the 10-minute rule, and live incidents
# close while their services are still broken. Differencing that gauge produced negative "new
# incident" counts in the first run. The opened counter only ever goes up, so it measures what the
# storm actually created regardless of what the resolver does to it afterwards.
incidents_opened() { sum_metric "$1" "sentinel_incidents_opened_total"; }
active_incidents() { sum_metric "$1" "sentinel_incidents_active"; }

# Micrometer sanitises dots in metric NAMES, not in label VALUES: the tag is topic="slo.breach.v1".
# Filtering on "slo_breach" matched nothing and the first run recorded 0 published events next to
# 400 created incidents — a self-contradictory record.
breaches_published() { sum_metric "$1" "sentinel_publish_total" 'topic="slo.breach'; }
breach_lag() { max_metric "$1" "kafka_consumer_fetch_manager_records_lag" "slo_breach"; }

# The direct collapse measure: mean services per correlated component. CLAUDE.md calls this the
# money metric. A mean of 1.0 means correlation suppressed nothing; chains are 5 deep, so a healthy
# reading approaches 5.0. Reported as a delta so each step is measured on its own breaches.
component_count() { sum_metric "$1" "sentinel_correlation_component_size_count"; }
component_sum() { sum_metric "$1" "sentinel_correlation_component_size_sum"; }
dlt_total() { sum_metric "$1" "sentinel_consumer_dlt_total"; }
cycle_count() { sum_metric "$1" "sentinel_slo_cycle_duration_seconds_count"; }
cycle_sum() { sum_metric "$1" "sentinel_slo_cycle_duration_seconds_sum"; }

redis_zcard() { docker exec sentinel-redis-1 redis-cli ZCARD breaches:recent 2>/dev/null | tr -d '\r' || echo 0; }
redis_mem() { docker exec sentinel-redis-1 redis-cli INFO memory 2>/dev/null | awk -F: '/used_memory_human/{gsub(/\r/,"");print $2}' || echo "?"; }
mem_of() { docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' 2>/dev/null | awk -v n="$1" '$1==n{print $2}'; }

# Total container memory as a percentage of the VM, so the run can stop before it starts swapping —
# a VM that swaps measures the swap.
mem_pct_of_vm() {
  docker stats --no-stream --format '{{.MemUsage}} {{.MemPerc}}' 2>/dev/null \
    | awk '{gsub(/%/,"",$NF); s+=$NF} END {printf "%.0f", s+0}'
}

log() { echo "$*"; echo "$*" >> "${OUT}"; }

mkdir -p "$(dirname "${OUT}")"
{
  echo "# Breach storm ramp — measurements 2 and 3"
  echo ""
  echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Fractions: ${FRACTIONS}   (cumulative: the exporter breaches chains 0..N)"
  echo ""
  echo '```'
} > "${OUT}"

total=$(curl -fsS "${EXPORTER_URL}/status" | python -c "import sys,json; print(json.load(sys.stdin)['totalServices'])")
log "Fleet: ${total} synthetic services, chains of 5 → $((total / 5)) chains"

# State the fleet you meant to measure, and fail if the stack disagrees.
#
# SYNTHETIC_SERVICES lives in the shell that ran `docker compose up`, so a new terminal silently
# falls back to the compose default of 100. Everything downstream still succeeds — the stack is
# healthy, seeding reports no failures — and the run quietly measures a fortieth of the intended
# fleet. Declaring the expectation is the only thing that catches it.
if [[ -n "${EXPECT_SERVICES:-}" && "${total}" != "${EXPECT_SERVICES}" ]]; then
  echo "" >&2
  echo "  Fleet is ${total} services but EXPECT_SERVICES=${EXPECT_SERVICES}." >&2
  echo "  The stack was almost certainly started without SYNTHETIC_SERVICES set." >&2
  echo "  Rebuild with it exported, or unset EXPECT_SERVICES to measure what is actually running." >&2
  exit 1
fi

# The graph is what makes correlation mean anything: without edges every breach is a component of
# one and the collapse ratio is 1:1 by construction, which reads as a working measurement.
#
# Polled rather than read once. DependencySeeder is an ApplicationRunner, so Spring runs it AFTER
# the web server starts answering /actuator/health — the graph lands a couple of seconds after the
# stack looks ready. At 100 services the seed wins that race and at 4,000 it does not, which is the
# kind of difference that only shows up at the size you actually care about.
expected_edges=$(( (total / 5) * 4 ))
edges=0
for _ in $(seq 1 30); do
  edges=$(docker exec sentinel-postgres-1 psql -U sentinel -d sentinel -tAc \
    "select count(*) from service_dependency where service_name like 'synth%';" 2>/dev/null | tr -d '\r ' || echo 0)
  [[ "${edges}" -ge "${expected_edges}" ]] && break
  sleep 2
done
log "Dependency edges: ${edges} synthetic (expected ~${expected_edges})"
if [[ "${edges}" -eq 0 ]]; then
  echo "" >&2
  echo "  No synthetic dependency edges. Correlation has no graph to walk, so every breach would" >&2
  echo "  open its own incident and the collapse ratio would be exactly 1:1 — a confident, wrong" >&2
  echo "  number. Check sentinel.synthetic-topology-url and the sentinel startup log." >&2
  exit 1
fi
log ""

# Heal first, so the run owns its starting conditions rather than inheriting them.
#
# A fleet left breaching by an earlier run makes the first step measure nothing: the services are
# already broken, no new incidents open, and the row reads "none / n/a" while every later row is
# fine. That happened — an aborted smoke test left 10% breaching, and the 0.1 row of the following
# run was void because of it. A measurement that silently depends on what ran before it is not a
# measurement.
if [[ "${SKIP_HEAL:-0}" != "1" ]]; then
  healed=$(curl -fsS -X POST "${EXPORTER_URL}/heal" | python -c "import sys,json; print(json.load(sys.stdin)['breachingServices'])" 2>/dev/null || echo "?")
  log "Healed the fleet before starting (now ${healed} breaching)"
  log ""
fi

echo "Settling ${SETTLE_SECONDS}s so the evaluator has healthy history..."
sleep "${SETTLE_SECONDS}"

body=$(scrape)
log "Baseline: opened=$(incidents_opened "${body}" | cut -d. -f1)  active=$(active_incidents "${body}" | cut -d. -f1)  published=$(breaches_published "${body}" | cut -d. -f1)  redis_zset=$(redis_zcard)"
log ""
log "$(printf '%-6s %9s %8s %8s %8s %8s %7s %7s %9s %9s %8s %5s' \
    frac breach_sv first_s settle_s opened svc_new ratio compsz peak_lag published cycle_ms dlt)"
log "$(printf '%.0s-' {1..112})"

prev_breaching=0

for frac in ${FRACTIONS}; do
  before=$(scrape)
  b_incidents=$(incidents_opened "${before}")
  b_breaches=$(breaches_published "${before}")
  b_cyc_n=$(cycle_count "${before}")
  b_cyc_s=$(cycle_sum "${before}")
  b_comp_n=$(component_count "${before}")
  b_comp_s=$(component_sum "${before}")

  flip=$(curl -fsS -X POST "${EXPORTER_URL}/breach?fraction=${frac}")
  breaching=$(printf '%s' "${flip}" | python -c "import sys,json; print(json.load(sys.stdin)['breachingServices'])")
  t0=$(date +%s)

  first_s=""
  stable=0
  last=-1
  peak_lag=0

  while true; do
    now=$(date +%s)
    (( now - t0 > MAX_WAIT_SECONDS )) && break

    sleep "${POLL_SECONDS}"
    poll=$(scrape)
    cur=$(active_incidents "${poll}")
    cur_int=${cur%.*}
    lag=$(breach_lag "${poll}")
    (( lag > peak_lag )) && peak_lag=${lag}

    if [[ -z "${first_s}" && "${cur_int}" -gt "${b_incidents%.*}" ]]; then
      first_s=$(( $(date +%s) - t0 ))
    fi

    if [[ "${cur_int}" == "${last}" && "${cur_int}" -gt "${b_incidents%.*}" ]]; then
      stable=$((stable + POLL_SECONDS))
      (( stable >= STABLE_SECONDS )) && break
    else
      stable=0
      last=${cur_int}
    fi

    # Saturation guards. Once the pipeline has given out, the remaining steps measure the same
    # saturated system for another twenty minutes each. Stop at the knee and name it.
    if (( lag > LAG_ABORT )); then
      abort_reason="breach-topic consumer lag ${lag} exceeded ${LAG_ABORT} — consumer cannot drain"
      break
    fi
    mem_pct=$(mem_pct_of_vm)
    if (( mem_pct > MEM_ABORT_PCT )); then
      abort_reason="container memory ${mem_pct}% of VM exceeded ${MEM_ABORT_PCT}% — next stop is swap"
      break
    fi
  done

  settle_s=$(( $(date +%s) - t0 ))
  after=$(scrape)
  a_incidents=$(incidents_opened "${after}")
  a_breaches=$(breaches_published "${after}")
  a_cyc_n=$(cycle_count "${after}")
  a_cyc_s=$(cycle_sum "${after}")
  a_comp_n=$(component_count "${after}")
  a_comp_s=$(component_sum "${after}")

  compsz=$(python -c "
dn = float('${a_comp_n}') - float('${b_comp_n}')
ds = float('${a_comp_s}') - float('${b_comp_s}')
print(f'{ds/dn:.2f}' if dn > 0 else 'n/a')")

  new_incidents=$(python -c "print(int(float('${a_incidents}') - float('${b_incidents}')))")
  new_breaches=$(python -c "print(int(float('${a_breaches}') - float('${b_breaches}')))")
  new_services=$((breaching - prev_breaching))

  # The collapse ratio is DISTINCT SERVICES BREACHING per incident, not events published per
  # incident. The evaluator republishes a breach every cycle for as long as an SLO stays broken, so
  # publish_total grows with step duration and would report ~160:1 over a four-minute step — a
  # number about how long we waited, not about how well correlation works. A naive alerting system
  # pages once per broken service; that is the honest denominator.
  ratio=$(python -c "n=${new_incidents}; print(f'{${new_services}/n:.2f}' if n>0 else 'n/a')")
  cycle_ms=$(python -c "
dn = float('${a_cyc_n}') - float('${b_cyc_n}')
ds = float('${a_cyc_s}') - float('${b_cyc_s}')
print(f'{ds/dn*1000:.0f}' if dn > 0 else 'n/a')")

  log "$(printf '%-6s %9s %8s %8s %8s %8s %7s %7s %9s %9s %8s %5s' \
      "${frac}" "${breaching}" "${first_s:-none}" "${settle_s}" "${new_incidents}" \
      "${new_services}" "${ratio}" "${compsz}" "${peak_lag}" "${new_breaches}" \
      "${cycle_ms}" "$(dlt_total "${after}" | cut -d. -f1)")"

  log "        resources: sentinel=$(mem_of sentinel-sentinel-1) redis=$(mem_of sentinel-redis-1)/$(redis_mem) redpanda=$(mem_of sentinel-redpanda-1) postgres=$(mem_of sentinel-postgres-1) prometheus=$(mem_of sentinel-prometheus-1)  [$(mem_pct_of_vm)% of VM]"
  log "        redis ZCARD=$(redis_zcard)   incidents opened cumulative=${a_incidents%.*}   still active=$(active_incidents "${after}" | cut -d. -f1)"
  log ""

  if [[ -n "${abort_reason}" ]]; then
    log "ABORTED at fraction ${frac}: ${abort_reason}"
    log ""
    log "This is the finding, not a failure of the run. The pipeline saturated here; steps beyond"
    log "this point would spend twenty minutes each re-measuring an already-saturated system."
    break
  fi

  prev_breaching=${breaching}
done

log ""
final=$(scrape)
log "Cumulative: opened=$(incidents_opened "${final}" | cut -d. -f1)  still active=$(active_incidents "${final}" | cut -d. -f1)  published=$(breaches_published "${final}" | cut -d. -f1)"
log "Component size overall: $(python -c "
n=float('$(component_count "${final}")'); s=float('$(component_sum "${final}")')
print(f'{s/n:.2f} services per incident' if n>0 else 'n/a')")"
if [[ -n "${abort_reason}" ]]; then
  log ""
  log "RUN STOPPED EARLY — ${abort_reason}"
fi
echo '```' >> "${OUT}"

echo ""
echo "Written to ${OUT}"
echo ""
echo "  Read the ratio column: chains are 5 deep, so ~5.00 means correlation is collapsing a whole"
echo "  chain into one incident. Near 1.00 means it is doing nothing. Above 5 means chains that"
echo "  should be separate are being merged."
echo ""
echo "  Read cycle_ms against the ~232ms healthy baseline at 4000 services: the evaluator records"
echo "  and publishes every breach inside the timed cycle, so this is the cost the throughput ramp"
echo "  never measured."
