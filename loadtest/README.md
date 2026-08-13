# Load testing

Five measurements, each producing a number that goes into
[`docs/LOAD_TEST_RESULTS.md`](../docs/LOAD_TEST_RESULTS.md).

This file is the operator's guide — what each script does and how to run it. The reasoning behind
the parameter values, and what the results prove about scaling up, is in
[`docs/BENCHMARK_METHODOLOGY.md`](../docs/BENCHMARK_METHODOLOGY.md).

## The trick: synthetic-exporter

You do not need 1000 real services to prove 1000 services. The evaluator queries Prometheus for
time series and does not care what produced them, so `synthetic-exporter` exposes N fake services'
worth of series from a single process. The load-test knob is one environment variable.

The eight real fleet services stay for the demo — they cascade properly and tell the story. The
exporter exists purely for stress testing and is never started by `make demo`.

Synthetic services are named `synth-c<chain>-s<depth>` and arranged in chains of five:

```
synth-c000-s0 -> synth-c000-s1 -> synth-c000-s2 -> synth-c000-s3 -> synth-c000-s4
synth-c001-s0 -> ...                                                (disconnected from c000)
```

Chains matter. A flat list of unrelated services would produce one incident per breach and an alert
collapse ratio of exactly 1:1, which measures nothing. Breaking a whole chain is what correlation is
supposed to collapse; keeping chains disconnected from each other is what stops it collapsing
everything into one.

## Scripts

| File | Measurement |
|---|---|
| `k6/seed-synthetic-slos.js` | Setup — two SLOs per synthetic service. Run this first, always. |
| `k6/evaluation-throughput.js` | 1. Cycle p50/p95/p99 and interval drift |
| `k6/breach-storm.js` | 2 and 3. End-to-end latency, and the alert collapse ratio |
| `k6/duplicate-replay.js` | 5. 10,000 duplicates, zero duplicate incidents |
| `../scripts/recovery-test.sh` | 4. Kill-to-steady-state, zero duplicates |
| `../scripts/load-test.sh` | Orchestrates the full ramp for measurement 1 |
| `k6/baseline.js` | Not a measurement — the demo's constant traffic generator |

## Running

```bash
make load-test-up SYNTHETIC_SERVICES=500   # stack + exporter, no demo fleet
make load-test-seed                        # 1000 SLOs
make load-test-storm                       # measurements 2 and 3
make load-test-replay                      # measurement 5
./scripts/recovery-test.sh                 # measurement 4
make load-test-down

make load-test                             # the full ramp — hours
```

## Things that will silently ruin a run

**Forgetting to seed.** The evaluator only evaluates SLOs that exist. An unseeded run measures an
empty cycle and reports a wonderfully fast p99 that means nothing.

**Leaving the demo fleet up.** Eight JVMs and a k6 baseline generator competing with Sentinel for
the same cores measures the laptop, not the evaluator. The load-test overlay does not start them;
do not start them by hand.

**Reusing Prometheus data between sizes.** Series from the previous run are still there and still
being evaluated by the recording rules. `scripts/load-test.sh` does `down -v` between steps for
exactly this reason.

**Not letting the windows fill.** The `loadtest` profile drops `minimum-coverage` to 0.05 so a run
does not have to wait 45 minutes for a 1h window to be 75% populated. Without that the evaluator
returns `InsufficientData` for everything and does no real work — again, a fast cycle that measures
nothing.

**The `loadtest` Spring profile must be active** for `duplicate-replay.js`. The replay endpoint is
`@Profile("loadtest")` and returns 404 otherwise, which is deliberate: an endpoint that floods the
breach topic on demand has no business existing in a real deployment.
