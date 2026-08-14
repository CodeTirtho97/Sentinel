# Load test results

This file is the *record*: what was measured, on what hardware. Why these five measurements, why
these parameter values, and what the results license you to claim about scaling are in
[BENCHMARK_METHODOLOGY.md](BENCHMARK_METHODOLOGY.md).

> **All five measurements complete, 2026-08-13/15.** Measurement 1 across a 40x range
> (100-4,000 synthetic service series); measurements 2-5 at 4,000 series / 8,000 SLOs.

---

## Headline results

The numbers that describe what the system does and how well it holds up. Everything below this
section is the working: methodology, the shape of the curves, and the defects the load test found.

| | Measured | What it means |
|---|---|---|
| **Alert collapse** | **5.00 : 1** | 2,000 failing services produced **400 incidents**, not 2,000 pages. Mean correlated component size 5.00 — the maximum possible for five-deep chains |
| **Evaluation scale** | **8,000 SLOs / 4,000 series** | evaluated every **15 s**, single instance, `shardCount=1` |
| **Cycle latency** | **p99 ≤ 500 ms** | 3.3% of the 15 s interval — **30× headroom**. Ceiling never reached |
| **Query cost** | **constant 30 queries** | independent of fleet size, because burn rate is precomputed in recording rules |
| **Detection latency** | **123–138 s** | against 115 s predicted from the burn-rate arithmetic |
| **Idempotency** | **10,000 → 1** | 10,000 duplicate events produced exactly one incident, one timeline row, zero dead-letters |
| **Recovery** | **40 s** | mid-cycle `docker kill` → restart → evaluating again, zero duplicate correlation keys |
| **Under storm** | **0 dead-letters** | at 50% of the fleet breaching: lag bounded at 182–356, memory 31–33% of an 8 GB VM |

**The one to lead with is 5:1.** It is the product working, expressed as a number — the difference
between an on-call engineer receiving 2,000 alerts and receiving 400 incidents, each naming the
service that caused it.

**The one that survives scrutiny is "ceiling not reached."** Cycle cost is linear in fleet size with
a shallow slope (`32.2 ms + 0.0497 ms/service`, R² = 0.9904 across a 40× range), and the test rig ran
out of memory at roughly 16,000 series before the evaluator ran out of interval budget.

---

## Environment

Record this exactly. A throughput number without the hardware it was measured on is not a result.

```
Machine:      Intel Core Ultra 5 125H — 14 cores / 18 threads, 15.6 GB RAM
OS:           Windows 11 Home Single Language 10.0.26200
Docker:       29.5.2, WSL2 backend (kernel 6.18.33.2-microsoft-standard-WSL2)
              VM limited via .wslconfig to memory=8GB / processors=8 / swap=2GB
              measured inside the VM: MemTotal 7.76 GiB, SwapTotal 2.00 GiB, nproc 8
Java:         Eclipse Temurin 21.0.11+10 LTS (eclipse-temurin:21-jre-alpine)
Sentinel:     single instance, shardCount=1
Prometheus:   v2.53.0, 15s scrape, 15s rule evaluation
```

The ramp runs 100 → 4,000 synthetic services (200 → 8,000 SLOs), a **40× range**. An earlier draft
capped it at 500 on a memory argument that the measurement then falsified — see below.

### Measured resource use, at both ends of the ramp

`docker stats --no-stream` taken with the stack under load at the end of each step:

| Container | @ 500 svc | @ 4,000 svc |
|---|---|---|
| sentinel | 554.7 MiB | 624.7 MiB |
| **prometheus** | **148.0 MiB** | **868.5 MiB** |
| synthetic-exporter | 122.0 MiB | 295.2 MiB |
| redpanda | 184.3 MiB | 184.7 MiB |
| postgres | 52.9 MiB | 54.4 MiB |
| grafana | 45.5 MiB | 45.6 MiB |
| redis | 4.1 MiB | 4.0 MiB |
| **total** | **~1.11 GiB (14%)** | **~2.03 GiB (26%)** |
| head series | 42,534 | **333,031** |
| Prometheus CPU | — | 96.8% ≈ **1 core of 8** |
| `slo_burn_rate_fast` rule eval | — | **2.665 s of a 15 s budget (18%)** |

**Memory-per-series is not constant, which is why every pre-run projection was wrong.** It falls from
3.56 KiB/series at 42.5k to **2.61 KiB/series at 333k**, because a fixed Prometheus base was being
attributed to the linear term. Three successive projections over-estimated memory — by 7× at 500,
and by ~2× at 4,000. The *series* arithmetic, by contrast, is reliable: 333,031 measured against
340,272 predicted, a 2% error.

**Nothing was saturated at 4,000 services.** Rules ran at 18% of their evaluation budget, memory at
26% of the VM, Prometheus at roughly one core of eight, and zero queries failed at any size. The
bottleneck ordering in [SCALING.md](SCALING.md) — Prometheus before the evaluator — remains
unfalsified because it was never reached.

Extrapolating measured memory at 2.61 KiB/series, the 8 GB VM would bind somewhere around **16,000
services (~1.3M series)**, with the evaluator still using roughly 10% of its interval budget. That
is the rig's limit, and it is the one that would end the experiment — not the evaluator's.

One thing that will otherwise invalidate the run:

- **Nothing else running.** Browsers, IDEs, and the demo fleet all compete for the same cores. The
  load-test overlay deliberately does not start the demo fleet or the k6 baseline generator, and the
  VM is pinned to 8 of 18 logical processors so runs are comparable to each other.

---

## 1. Evaluation throughput — the ceiling

Ramps `SYNTHETIC_SERVICES` and samples `sentinel_slo_cycle_duration_seconds`. Two SLOs per service
(one availability, one latency).

| Services | SLOs | Head series | Cycles | Mean | p50 | p95 | p99 | p99 as % of 15 s | Drift | Query failures |
|---|---|---|---|---|---|---|---|---|---|---|
| 100   | 200   | ~8k       | 80 | 31 ms  | ≤ 28 ms  | ≤ 89 ms  | ≤ 179 ms | 1.19% | 0.5 s | 0 |
| 250   | 500   | ~21k      | 80 | 44 ms  | ≤ 45 ms  | ≤ 112 ms | ≤ 179 ms | 1.19% | 0.5 s | 0 |
| 500   | 1,000 | 42,534    | 80 | 59 ms  | ≤ 50 ms  | ≤ 179 ms | ≤ 246 ms | 1.64% | 0.5 s | 0 |
| 1,000 | 2,000 | ~85k      | 80 | 94 ms  | ≤ 89 ms  | ≤ 201 ms | ≤ 246 ms | 1.64% | 0.8 s | 0 |
| 2,000 | 4,000 | ~170k     | 80 | 123 ms | ≤ 134 ms | ≤ 246 ms | ≤ 358 ms | 2.39% | 0.4 s | 0 |
| 4,000 | 8,000 | **333,031** | 79 | **232 ms** | ≤ 246 ms | ≤ 447 ms | **≤ 500 ms** | **3.33%** | 15.5 s | 0 |

Six sizes, 20 minutes of sampling each (~80 cycles per row), `down -v` between steps. Two runs of
three steps: 66m42s and 67m17s. Every step reported the expected SLO count and zero query failures.

```
Ceiling: NOT REACHED.
  p99 at 4,000 services is 500 ms against a 15,000 ms interval — 3.3% of the budget, 30x headroom.
  The ramp stopped because 4,000 was chosen in advance, not because anything degraded.

First bottleneck observed: none. At 4,000 services Prometheus rule evaluation was at 18% of its
  15s budget, memory at 26% of the VM, and CPU at roughly one core of eight.

Rig limit (not evaluator limit): ~16,000 services, extrapolated from measured memory-per-series.
```

### The shape, which is the actual finding

Fitting the **mean** — the percentiles are bucket-derived and land on boundaries, so they are the
wrong series to regress — across all six points:

```
cycle_mean(N) ≈ 32.2 ms + 0.0497 ms/service × N        R² = 0.9904

  N=  100  measured  31 ms   fitted  37.2 ms   resid  -6.2
  N=  250  measured  44 ms   fitted  44.6 ms   resid  -0.6
  N=  500  measured  59 ms   fitted  57.0 ms   resid  +2.0
  N= 1000  measured  94 ms   fitted  81.9 ms   resid +12.1
  N= 2000  measured 123 ms   fitted 131.5 ms   resid  -8.5
  N= 4000  measured 232 ms   fitted 230.8 ms   resid  +1.2
```

**This is the cost model from [BENCHMARK_METHODOLOGY.md §6](BENCHMARK_METHODOLOGY.md) confirmed by
measurement**: a constant term `a` for the 30 instant queries, plus a linear term `b·N` for parsing
N samples out of each response. No hidden superlinearity, which is what the ramp existed to check.

**The log-log slope is 0.534, and that is not evidence of sublinearity.** It is the expected
signature of `a + b·N` when the constant dominates at small N: the fixed 32 ms is 87% of the cycle
at N=100 and 14% at N=4,000. A straight line fits better than a power law (R² 0.990 vs 0.978).

### How far extrapolation actually holds — demonstrated, not asserted

The three-point fit from 100/250/500 was `25.1 + 0.0690·N`. It was used to **predict the next three
points before they were measured**:

| N | Predicted from 3 points | Measured | Error |
|---|---|---|---|
| 1,000 | 94.1 ms | **94 ms** | **−0.1%** |
| 2,000 | 163.1 ms | 123 ms | −24.6% |
| 4,000 | 301.0 ms | 232 ms | −22.9% |

Exact at 2× beyond the fitted range, then over-predicting by ~23% at 4× and 8×. The short baseline
had over-estimated the slope (0.0690 vs the six-point 0.0497). **This is the most useful thing in
this document**: it shows empirically that extrapolation from this rig is trustworthy to roughly
double the measured range and degrades beyond it — which is a far better answer to "how do you know
it scales?" than any single throughput figure.

Extrapolating the six-point fit: the mean reaches the 15 s interval near 300,000 services, and p99
(≈2.15× the mean at 4,000) near 140,000. Quote either only as an order of magnitude. Long before
them, the limits in [BENCHMARK_METHODOLOGY.md §6](BENCHMARK_METHODOLOGY.md) — Prometheus rule cost,
then GC — stop the model being true, and the rig gives out at ~16,000. The defensible sentence is: **"measured flat-sloped and linear from 100 to
4,000 services; ceiling not reached; the rig ran out before the evaluator did."**

**Every p99 here is an upper bound.** It is derived from Micrometer's histogram buckets, so the
reported figure is the smallest bucket boundary at or above the true p99. `application.yml` places
an explicit boundary at 15s so "is p99 approaching the interval" is answerable exactly rather than
by interpolation. Quote these as "p99 ≤ X ms".

**Read drift, not "cycles missed."** `@Scheduled(fixedDelay = 15s)` measures the gap *after* the
previous cycle returns, so cycles never overlap and none is ever skipped — the schedule slips
instead. Drift is `(wall time) − (cycles × 15s)`. Zero means the evaluator is keeping up; growing
drift says by how much it is not, and the rate of growth is the interesting part.

**The 15.5 s drift at 4,000 services is not the evaluator falling behind.** It is exactly one fewer
completed cycle (79 rather than 80), and that is precisely what `fixedDelay` predicts: the period is
`15 s + cycle duration`, so at a 232 ms mean you get 1200 / 15.232 = 78.8 → 79 cycles. The "drift" is
accumulated execution time, 1.3% of wall clock. With a 232 ms cycle against a 15 s interval, falling
behind is not arithmetically possible.

This exposes a **weakness in the metric as defined**: because `cycles` is an integer, drift is
quantised in ~15 s steps and jumps discontinuously when a single cycle is gained or lost. At these
cycle durations it is a coarse pass/fail indicator rather than the smooth, gradually-growing signal
the definition implies. It would only become the intended continuous measure once cycle duration is
a significant fraction of the interval.

**"Flat" was the wrong word, and the measurement says so.** An earlier draft of this file predicted
a roughly flat curve. It is not flat: the mean grows from 31 ms to 59 ms between 100 and 500
services, very nearly doubling. That is not a problem, and it was never what the cost model actually
claimed — [BENCHMARK_METHODOLOGY.md §6](BENCHMARK_METHODOLOGY.md) says `cycle(N) ≈ a + b·N`, which is
**linear, not constant**. Only the *query count* is constant; response parsing is O(N) by
construction.

What the ramp was really testing is whether the slope is small and straight, and it is: ~0.069 ms per
service, straight to within 1.6 ms over a 5× range. The failure mode being ruled out was a curve
bending *upward*, which would mean a per-service query hiding somewhere. Nothing bends.

**Insufficient-data share stayed near 20% at every size** — 17.4% / 20.9% / 19.8%. That is window
population, not idleness: `SloEvaluator.fetchAll` runs unconditionally before any coverage verdict,
so the queries and parsing that dominate the timing happen regardless. On a freshly started stack a
3d window can never reach its coverage floor, so MEDIUM severity is unexercised throughout. Being
consistent across all three sizes is what makes the rows comparable to each other.

---

## 2 & 3. Breach storm and alert collapse

Run as a **ramp** rather than a single shot — 10% of chains, then 20%, 30%, 40%, 50% — because one
30% run tells you only whether 30% works. A ramp shows the shape and finds the knee if there is one.
50% is the deliberate ceiling: past half the fleet failing you are measuring a different steady
state in which the breach path *is* the normal path, not a storm.

Fractions are cumulative by construction (the exporter breaches chains `0..N`), so every row is a
delta against the previous step. 4,000 services in 800 chains of five.

| Fraction | Services breaching | Time to first incident | Settle | Incidents opened | **Collapse ratio** | **Component size** | Peak lag | Events published | Cycle | DLT |
|---|---|---|---|---|---|---|---|---|---|---|
| 10% | 400 | *void — see below* | — | — | — | 5.00 | 251 | 3,200 | 311 ms | 0 |
| 20% | 800 | 129 s | 220 s | **80** | **5.00** | **5.00** | 251 | 2,800 | 352 ms | 0 |
| 30% | 1,200 | 138 s | 232 s | **80** | **5.00** | **5.00** | 182 | 4,000 | 413 ms | 0 |
| 40% | 1,600 | 123 s | 218 s | **80** | **5.00** | **5.00** | 356 | 4,400 | 426 ms | 0 |
| 50% | 2,000 | 137 s | 228 s | **80** | **5.00** | **5.00** | 187 | 6,800 | 551 ms | 0 |

```
Cumulative:  2,000 services breaching  ->  400 incidents opened
             sentinel_correlation_component_size (mean) = 5.00
             ALERT COLLAPSE RATIO = 5.00:1
             Incidents still active = 400  (none falsely auto-resolved)
             Dead-lettered = 0
```

**5.00:1 is the theoretical maximum for five-deep chains**, and the component-size summary reports it
with no rounding: every correlation walk found exactly five services and produced exactly one
incident naming the leaf. 2,000 services broke; a naive alerting system pages 2,000 times; Sentinel
opened 400 incidents.

**Detection at 123–138 s matches the prediction.** The breaching profile puts 45% of requests over
the latency threshold, which against a 0.999 objective is a burn rate of ~450. Crossing the CRITICAL
threshold of 14.4 on a 1h long window therefore takes `14.4 × 0.001 / 0.45 × 3600 ≈ 115 s`. Measured
detection landed 8–23 s above that, which is the evaluation interval plus scrape alignment.

**Nothing saturated at 50%.** Peak consumer lag stayed between 182 and 356 and *fell* between steps;
memory held at 31–33% of the VM; no abort guard triggered; zero dead-letters. Cycle time grew from
the 232 ms healthy baseline to 551 ms — the cost of recording and publishing breaches inside the
timed cycle, which measurement 1 cannot see because nothing breaches during it.

### The 10% row is void, and why

An aborted smoke test left the fleet 10% breached before the run started, so the first step had
nothing new to break: 0 incidents opened, no first-incident time, and the poll ran to its timeout.
The ramp's own baseline line recorded the contamination (`opened=80 active=80 published=7200`).

The cause was a shell subtlety worth naming: `${FRACTIONS:-default}` substitutes the default when
the variable is **empty as well as unset**, so a run intended to inject nothing injected 10%.
`breach-ramp.sh` now heals the fleet before starting, so a run owns its initial conditions instead
of inheriting them.

The remaining four steps and the cumulative figure are unaffected — each added exactly 400 services
and opened exactly 80 incidents at 5.00.

---

## 4. Recovery — durability with a number

`docker kill` mid-cycle, then restart. Run `./scripts/measure.sh recovery`.

```
Cycles completed before kill:  245
Kill-to-steady-state:          40 s
Incidents before / after:      200 / 200
Duplicate active correlation keys: 0   (expected 0)
PASS
```

Killed with 400 incidents open and 8,000 SLOs under evaluation. Forty seconds covers container
restart, Spring Boot startup, Flyway validation, dependency-graph seeding, Kafka group rejoin, and
two completed evaluation cycles.

"Steady state" means the evaluator has completed a cycle after coming back, not that the health
endpoint returned 200 — a process that is up but not evaluating has recovered nothing. The script
waits on `sentinel_slo_cycle_duration_seconds_count` advancing, then settles 20 s so anything Kafka
redelivers after the rebalance has landed before counting.

**One caveat on the counts.** `incidents before/after` are read from `/incidents?size=200`, so both
are truncated at 200 of the ~400 open. The number that matters — duplicate correlation keys among
unresolved incidents — is computed over the same page, so it is a 200-incident sample rather than a
census. Zero duplicates in that sample, alongside the 10,000-event replay below, is the evidence for
the idempotency claim; neither alone would be.

Zero duplicates is the claim being tested. Manual ack means the offset was never committed for the
in-flight message, so Kafka redelivers it after the rebalance; the deterministic event ID makes the
redelivery recognisable, and the partial unique index makes acting on it harmless.

---

## 5. Duplicate replay — correctness under duplication

Not a performance number. This is the evidence behind the idempotency claim.

```
Events replayed:      10,000   (published in 23 ms)
Target service:       synth-c400-s4
Incidents created:    1   (expected 1)
Duplicate incidents:  0   (expected 0)
Breach timeline rows: 1   (expected 1)
Drain time:           50.8 s
Dead-lettered:        0   (expected 0)
```

Targeted at `synth-c400-s4`, a chain that never breached during the storm. A service with an
incident already open would have *attached* to it rather than creating one, reporting zero created
and failing for a reason that has nothing to do with idempotency.

All 10,000 copies share one `eventId`, because the ID is derived from
`(sloId, severity, evaluationBucket)` and the replay holds `detectedAt` fixed. Three layers have to
cooperate for the answer to be 1:

| Layer | Catches |
|---|---|
| Deterministic event ID | Makes redelivery *recognisable* at all |
| Redis dedupe, set after commit | The cheap common case — skips work already done |
| Partial unique index | The commit↔mark race, and concurrent consumers on the same key |

---

## 6. What the load test found, and what changed because of it

The first storm run did not produce a number. It produced five defects, four of them real product
bugs rather than harness problems. This section is the actual return on the exercise: a throughput
figure only confirms a design, whereas the storm found the places where it broke.

| # | Defect | Mechanism | Fix |
|---|---|---|---|
| 1 | Collapse ratio locked at **exactly 1.00** | The synthetic fleet had **no dependency edges** — the exporter served `/topology.json`, nothing consumed it. Every breach was a component of one. | `DependencySeeder` fetches the topology under the `loadtest` profile and **fails startup** if it cannot |
| 2 | Consumer lag to **18,310**, unrecoverable | Correlation deserialized *every event in the window* per event — ~320M JSON parses per cycle, quadratic in storm size | Window stores service names, not events; `BreachRef` carries the only two fields correlation reads |
| 3 | **4,243 live incidents auto-resolved** while their services still burned | Lag froze `lastBreachAt`; the sweep read "quiet for 10 minutes" and closed them. A silent all-clear during the exact event the product exists for | Auto-resolve **refuses to run** while the breach consumer is behind, and counts the skip |
| 4 | **543,600 events** published per run | Every breach re-announced every 15 s cycle. Alertmanager's equivalent defaults to 4 hours | Announce on start, on severity change, and on a 2-minute heartbeat |
| 5 | **7,632 LLM calls** attempted in one storm | RCA drafted for every incident, unbounded | Bounded to CRITICAL by config; others still render the deterministic summary on demand |

### Measured, before and after

| | Broken | Fixed | Change |
|---|---|---|---|
| Component size (collapse) | 1.00 | **5.00** | at the theoretical maximum |
| Incidents per 2,000 breaching services | ~2,000 | **400** | 5× fewer |
| Peak consumer lag | 18,310 | **182–356** | ~50–100× |
| Cycle time under storm | 2,348 ms | **311–551 ms** | ~4–7× |
| Events published | 543,600 | **32,000** | ~17× |
| Falsely auto-resolved incidents | 4,243 | **0** | — |
| Peak memory | 63% of VM | **31–33%** | half |

### Two regressions introduced while fixing these, and caught by re-measuring

Worth recording because both were invisible at small scale and obvious at 4,000 services:

**Frozen-score fragmentation.** Preserving each service's *earliest* breach time for origin
inference meant its window entry stopped being refreshed — so after five minutes a service fell out
of its own correlation window while still failing, and the next heartbeat opened a second incident
keyed on itself. One chain of five produced five incidents, 307 s apart, which is exactly the
correlation window. Fixed by scoring the set by *latest* breach (membership) and keeping first
sightings in a companion hash (origin): two questions that want opposite answers, so two structures.

**Readiness before the graph exists — a known limitation, stated rather than fixed.**
`DependencySeeder` is an `ApplicationRunner`, so Spring runs it *after* the web server starts
answering `/actuator/health`:

```
17:46:03.690  Tomcat started            -> /actuator/health answers UP
17:46:06.358  seeded 3207 edges         -> 2.4s later
```

For those 2.4 seconds Sentinel reports healthy with an empty dependency graph, and a breach arriving
in that window would correlate against nothing — a component of one, keyed on itself. Invisible at
100 services because the seed wins the race; reproducible at 4,000 because 3,207 rows take longer to
write.

The blast radius is small and bounded: it is one startup window, the correlation key is frozen at
creation so a mis-keyed incident does not corrupt later ones, and in a real deployment the topology
comes from reviewed configuration rather than a runtime fetch. The principled fix is to put
"graph loaded" into the readiness contract next to the existing Kafka partition-assignment
indicator — the same rule the auto-resolve guard follows: **do not answer a question you do not yet
have the data for.** It is listed in the README's known limitations rather than silently carried.

### A property of burn-rate alerting, not a bug

Healing a service does not clear its incident. Burn rate is computed over a rolling window, so after
a 25-minute storm at burn ~450 the 1-hour window still reads ~187 — thirteen times the CRITICAL
threshold — with zero bad requests still arriving. **Recovery takes about as long as the window, not
as long as the fix.** This is why the auto-resolve demo works only under the `demo` profile's
compressed 2m windows, and it is worth stating before someone discovers it during a demo.

---

## How to reproduce

Budget ~2 hours of mostly-idle wall time. `docker compose down -v` first — the demo stack competes
for the same cores — and close the IDE and browsers for the real runs.

```bash
# 0. Smoke run. Do not skip it: the standard way to lose two hours is to run the
#    whole ramp and find every row says "no data".            ~8 min
SIZES="100" DURATION_MIN=3 ./scripts/load-test.sh

# 1. Evaluation throughput, the ramp: 100 / 250 / 500         ~75 min
make load-test
#    equivalently: SIZES="100 250 500" DURATION_MIN=20 ./scripts/load-test.sh

# 2 and 3. Breach storm and alert collapse                    ~20 min
make load-test-up SYNTHETIC_SERVICES=500
make load-test-seed
make load-test-storm

# 5. Duplicate replay                                         ~5 min
make load-test-replay

# 4. Recovery                                                 ~5 min
make load-test-recovery

make load-test-down
```

Without `make` (Git Bash on Windows), the same sequence is `./scripts/measure.sh seed|storm|replay|recovery`
after `SKIP_FLEET=1 ./scripts/wait-for-health.sh`. Both paths append to
`docs/LOAD_TEST_RESULTS.raw.md`.

After the smoke run, read `docs/LOAD_TEST_RESULTS.raw.md` and check four lines before committing to
the long ramp:

| Line | Must be | If it isn't |
|---|---|---|
| `SLOs evaluated:` | `200` | seeding failed — everything after is meaningless |
| `Cycles completed:` | ≈ `expected` | the evaluator is not running |
| `Cycle p50/p95/p99` | a millisecond figure | histogram buckets are not publishing |
| `Query failures:` | `0` | Prometheus is unreachable or the queries are malformed |

Then the subtlest bad run — every SLO returning `InsufficientData`, which produces a beautifully
fast cycle that measures nothing:

```bash
curl -s localhost:3000/actuator/prometheus | grep sentinel_slo_evaluations_total
```

`result="insufficient"` must not be ~100% of the total.

Raw k6 output from the ramp is written to `docs/LOAD_TEST_RESULTS.raw.md`. Transcribe it here —
that file is scratch, this one is the record.

### Why a synthetic exporter rather than real services

Proving 500 services does not require running 500 services. The evaluator queries Prometheus for
time series and has no idea what produced them, so `synthetic-exporter` exposes N fake services'
worth of series from one process. The load-test knob is a single environment variable instead of
500 containers and 32GB of RAM, which is what makes these numbers measurable on a laptop at all.

The eight real fleet services stay for the demo — they cascade properly and tell the story. The
exporter only ever stress-tests, and is never part of `make demo`.
