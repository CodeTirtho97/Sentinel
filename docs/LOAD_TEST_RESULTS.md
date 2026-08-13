# Load test results

This file is the *record*: what was measured, on what hardware. Why these five measurements, why
these parameter values, and what the results license you to claim about scaling are in
[BENCHMARK_METHODOLOGY.md](BENCHMARK_METHODOLOGY.md).

> **STATUS: NOT YET MEASURED.**
>
> Every number below is a placeholder. The harness is built and runnable — see
> [How to reproduce](#how-to-reproduce) — but these runs take hours of wall time on a dedicated
> machine, and a benchmark quoted from a laptop that was also doing something else is worse than no
> benchmark.
>
> **Do not write the résumé bullets in §18 of the build spec until this file has real numbers.**
> Fill in the tables from an actual run, then delete this block.

---

## Environment

Record this exactly. A throughput number without the hardware it was measured on is not a result.

```
Machine:      <CPU model, core count, RAM>
OS:           <version>
Docker:       <docker --version>, <memory allocated to the VM>
Java:         21 (<distribution and exact version>)
Sentinel:     single instance, shardCount=1
Prometheus:   v2.53.0, 15s scrape, 15s rule evaluation
```

Two things that will otherwise invalidate the run:

- **Nothing else running.** Browsers, IDEs, and the demo fleet all compete for the same cores. The
  load-test overlay deliberately does not start the demo fleet or the k6 baseline generator.
- **Docker memory ceiling.** At 2000 synthetic services the exporter alone wants ~1GB, and a VM that
  starts swapping measures the swap, not the evaluator.

---

## 1. Evaluation throughput — the ceiling

Ramps `SYNTHETIC_SERVICES` and samples `sentinel_slo_cycle_duration_seconds`. Two SLOs per service
(one availability, one latency).

| Services | SLOs | Cycle p50 | Cycle p95 | Cycle p99 | Interval drift |
|---|---|---|---|---|---|
| 100  | 200  | — | — | — | — |
| 250  | 500  | — | — | — | — |
| 500  | 1000 | — | — | — | — |
| 1000 | 2000 | — | — | — | — |
| 2000 | 4000 | — | — | — | — |

```
Ceiling: <N> services on one instance before p99 approaches the 15s interval.
First bottleneck observed: <what — see docs/SCALING.md for the candidates>
```

**Read drift, not "cycles missed."** `@Scheduled(fixedDelay = 15s)` measures the gap *after* the
previous cycle returns, so cycles never overlap and none is ever skipped — the schedule slips
instead. Drift is `(wall time) − (cycles × 15s)`. Zero means the evaluator is keeping up; growing
drift says by how much it is not, and the rate of growth is the interesting part.

**Expect this to be flat for a long time.** Because burn rate is precomputed in recording rules, a
cycle is ~6 Prometheus queries at any fleet size (see [SCALING.md](SCALING.md)). Going from 100 to
1000 services adds rows to a vector that was already being fetched, plus in-process arithmetic. If
the curve is *not* roughly flat up to several hundred services, something is querying per service
and that is the finding.

---

## 2. Breach storm — end-to-end latency under load

30% of synthetic chains flipped into breach simultaneously.

```
Services breaching:            <N>
Time to first incident:        <X> s
Time to settle (no new):       <Y> s
Peak consumer lag:             <N> messages
Dead-lettered:                 <N>   (expected 0)
```

Detection is bounded below by the SLO windows themselves: with production windows a breach cannot
be detected before the short window has enough data. What is being measured here is the *additional*
latency of correlation and incident creation under concurrent load, not the burn-rate arithmetic's
inherent delay.

---

## 3. Alert collapse — the product's value as a number

The headline number, and the one worth leading with.

```
Raw breaches:      <A>
Incidents created: <B>
Ratio:             <A/B>:1
```

Synthetic chains are five services deep, so a correctly correlating system approaches **5:1** — five
services breaking together become one incident naming the leaf. A ratio near 1:1 means correlation
found nothing and the component walk is not doing its job; that is a bug, not a result.

Also worth recording, since it is the same claim from the other direction:

```
sentinel_correlation_component_size (mean):  <N>
```

---

## 4. Recovery — durability with a number

`docker kill` mid-cycle, then restart. Run `./scripts/recovery-test.sh`.

```
Kill-to-steady-state:  <Y> s
Duplicate incidents:   0
```

"Steady state" means the evaluator has completed a cycle after coming back, not that the health
endpoint returned 200 — a process that is up but not evaluating has recovered nothing.

Zero duplicates is the claim being tested. Manual ack means the offset was never committed for the
in-flight message, so Kafka redelivers it after the rebalance; the deterministic event ID makes the
redelivery recognisable, and the partial unique index makes acting on it harmless.

---

## 5. Duplicate replay — correctness under duplication

Not a performance number. This is the evidence behind the idempotency claim.

```
Events replayed:     10,000
Incidents created:   1   (expected 1)
Duplicate incidents: 0   (expected 0)
Breach timeline rows: 1  (expected 1)
Drain time:          <Z> s
```

All 10,000 copies share one `eventId`, because the ID is derived from
`(sloId, severity, evaluationBucket)` and the replay holds `detectedAt` fixed. Three layers have to
cooperate for the answer to be 1:

| Layer | Catches |
|---|---|
| Deterministic event ID | Makes redelivery *recognisable* at all |
| Redis dedupe, set after commit | The cheap common case — skips work already done |
| Partial unique index | The commit↔mark race, and concurrent consumers on the same key |

---

## How to reproduce

```bash
# 1. Evaluation throughput, the full ramp (hours)
make load-test

#    or a single size:
SIZES="500" DURATION_MIN=10 ./scripts/load-test.sh

# 2 and 3. Breach storm and alert collapse
make load-test-up SYNTHETIC_SERVICES=500
make load-test-seed
make load-test-storm

# 4. Recovery
./scripts/recovery-test.sh

# 5. Duplicate replay
make load-test-replay

make load-test-down
```

Raw k6 output from the ramp is written to `docs/LOAD_TEST_RESULTS.raw.md`. Transcribe it here —
that file is scratch, this one is the record.

### Why a synthetic exporter rather than real services

Proving 1000 services does not require running 1000 services. The evaluator queries Prometheus for
time series and has no idea what produced them, so `synthetic-exporter` exposes N fake services'
worth of series from one process. The load-test knob is a single environment variable instead of
500 containers and 32GB of RAM, which is what makes these numbers measurable on a laptop at all.

The four real fleet services stay for the demo — they cascade properly and tell the story. The
exporter only ever stress-tests, and is never part of `make demo`.
