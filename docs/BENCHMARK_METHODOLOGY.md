# Benchmark methodology

Why these measurements, why these parameter values, how to run them, and what the results do and do
not license you to claim.

Three documents, deliberately separated:

| Document | Answers |
|---|---|
| **This one** | *Why* these numbers are the right ones to take, and what they prove |
| [`LOAD_TEST_RESULTS.md`](LOAD_TEST_RESULTS.md) | *What* was measured, on what hardware |
| [`SCALING.md`](SCALING.md) | *What breaks first* past the measured range, and the fix for each |

The reason for the split: a results table with no methodology is unfalsifiable, and a methodology
with no results is a plan. Both have to exist for either to be worth anything.

---

## 1. The five measurements, and the claim each one defends

Each measurement exists because there is a specific sentence someone might say about this project
that would otherwise be unsupported. A benchmark that does not correspond to a claim is decoration.

| # | Measurement | The claim it defends | What a bad result would reveal |
|---|---|---|---|
| 1 | Evaluation throughput | "evaluates N services on a 15s cycle" | a per-service query hiding somewhere — the curve would bend upward instead of staying flat |
| 2 | Breach storm | "detection latency holds under concurrent load" | consumer lag growing without bound; correlation serialising behind a hot key |
| 3 | Alert collapse ratio | "collapses an alert storm into one incident" — **the product's entire value** | a ratio near 1:1, meaning the component walk is not correlating at all |
| 4 | Recovery | "survives a mid-cycle kill with no duplicates" | duplicate incidents, i.e. the partial unique index or the ack ordering is wrong |
| 5 | Duplicate replay | "idempotent under redelivery" | more than one incident from 10,000 identical events |

Note that **only measurement 1 is about speed.** The other four are correctness claims that happen
to need load to expose. That balance is deliberate: for a system whose job is to *not* wake someone
at 2am for the wrong reason, being right under duplication matters more than being fast.

Measurement 3 is the one to lead with. It is the product working, expressed as a number.

---

## 2. Why synthetic series are a legitimate way to measure this

`synthetic-exporter` is one process that exposes N fake services' worth of Prometheus series. The
load-test knob is a single environment variable instead of 500 containers and 32GB of RAM.

**Why this is sound, not a shortcut:** the evaluator's entire interface to the outside world is
`MetricsSource`, which returns time series. It has no idea what produced them, and it cannot have —
that is the whole point of the seam. Proving the evaluation pipeline handles 500 services' worth of
series does not require 500 processes, any more than testing a JSON parser requires a real web
server.

**What it genuinely measures:** Prometheus query and rule-evaluation cost at realistic series
counts, response parsing, the burn-rate arithmetic across every SLO, the shard filter, event
publication, and — because the synthetic topology is chained — real correlation work in the
consumer.

**What it does not measure, and you must say so first:**

- No real network between services, so no cascading latency, no connection-pool exhaustion, no
  retry storms.
- The latency distribution is a fixed pair of bucket profiles (healthy and breaching), not a
  realistic one that drifts.
- Everything shares one machine, so Prometheus, Postgres and Sentinel contend for the same cores in
  a way they would not in a real deployment.

The eight real fleet services exist precisely for what synthetic series cannot show: a genuine
cascade through synchronous HTTP calls. The two halves of the test strategy cover different things,
and neither is a substitute for the other.

**Phrase it as "500 synthetic service series", never "500 services".** Someone will ask how you
ran 500 JVMs on a laptop. Volunteering the exporter trick is a good moment; being caught by
it is not.

---

## 3. The parameters, and the reasoning behind each value

### `SYNTHETIC_SERVICES` — the ramp: 100 / 250 / 500

The exporter emits **9 raw series per service**: six histogram buckets (`le` = 0.1, 0.25, 0.5, 1.0,
2.0, +Inf), one `_sum`, and `_count` split across status 200 and 500. The recording rules then
derive **74 more** — 9 per window (one error ratio, six latency-ratio buckets, requests, samples)
across five fast windows and three slow ones, plus two 30d budget series.

**≈83 active series per synthetic service.**

| Services | SLOs | Active series | Samples parsed per cycle | Prometheus RSS (est.) |
|---|---|---|---|---|
| 100 | 200 | ~8k | 3k | ~0.4 GB |
| 250 | 500 | ~21k | 8k | ~0.7 GB |
| 500 | 1,000 | ~42k | 15k | ~1 GB |
| ~~1,000~~ | ~~2,000~~ | ~~83k~~ | ~~30k~~ | ~~~1.5–2 GB~~ |

RSS is an estimate, not measured. **The ramp stops at 500, and the reason is the test rig rather
than the system under test.** That distinction matters, so state it plainly rather than letting
someone infer a ceiling that was never hit:

1. **The host is 15.6 GB.** Giving WSL2 the 10 GB an earlier draft of this document assumed would
   leave Windows and Docker Desktop under 6 GB. At the 8 GB actually allocated, the expectation was
   that Postgres, Redpanda, Redis, Sentinel and Grafana would take ~3 GB before Prometheus and the
   exporter were counted, putting 1,000 services close enough to the ceiling that the VM might swap.
   **A VM that swaps measures the swap.**

   > **Measured afterwards: this reasoning was wrong, and wrong by a lot.** At 500 services the
   > entire stack used **~1.11 GiB of 7.755 GiB** — Prometheus 148 MiB against an estimated ~1 GB,
   > Sentinel 555 MiB, everything else under 200 MiB each. The series arithmetic held (42,534 head
   > series against a predicted ~42k); the memory-per-series assumption did not. 1,000 services
   > would almost certainly have fit. See
   > [LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md#measured-resource-use-at-500-services-1000-slos-42534-head-series).
   > The cap held for reason 2 below, which the measurement did not disturb.
2. **A larger top end would not find the ceiling anyway.** See §6 — query count is constant in N, so
   if p99 at 500 is two orders of magnitude below the 15s interval, doubling N leaves it one order
   below. Another point extends the flat line rather than terminating it.
3. **The time is better spent on longer runs.** Which is the next parameter.

Three points is the minimum that establishes a *shape* rather than a pair of dots, and shape is the
finding. What this ramp licenses is "flat from 100 to 500 services, ceiling not reached" — not a
claim about 1,000, which was not measured and should not be implied.

### `DURATION_MIN = 20` — because a p99 over 40 samples is not a p99

At a 15s interval, a 10-minute sample is ~40 cycles. The 99th percentile of 40 observations is
effectively "the slowest one", and a single GC pause moves it. Twenty minutes gives ~80 cycles plus
the settle period — still not a large sample, but enough that the number is not one outlier wearing
a hat.

Trading a larger top end for doubled duration at three sizes costs nothing in coverage and buys
a materially more defensible tail — especially since, per §6, the extra point would have landed on
the same flat line.

**A resolution caveat to state when quoting p99.** It is derived from Micrometer's histogram
buckets, so the reported value is *the smallest bucket boundary at or above the true p99* — an upper
bound. `application.yml` places an explicit boundary at 15s so "is p99 approaching the interval" is
answerable exactly rather than by interpolation. Quote it as "p99 ≤ X ms".

### `SYNTHETIC_CHAIN_LENGTH = 5` — chains, not a flat list

Synthetic services are arranged in disconnected chains of five:

```
synth-c000-s0 -> synth-c000-s1 -> synth-c000-s2 -> synth-c000-s3 -> synth-c000-s4
synth-c001-s0 -> ...                                          (disconnected from c000)
```

This is not cosmetic. A flat list of unrelated services would produce **one incident per breach and
a collapse ratio of exactly 1:1** — measurement 3 would report the product doing nothing, and would
be right. Breaking whole chains is what correlation is supposed to collapse; keeping chains
disconnected from one another is what stops it collapsing *everything* into one and reporting a
gloriously meaningless 500:1.

Depth 5 sets the theoretical ceiling for the collapse ratio at **5:1**. A measured ratio near 5:1
means correlation is working; near 1:1 means the component walk is broken; above 5:1 means chains
are being merged that should not be.

### `FRACTION = 0.3` — a third of chains breaching at once

High enough to be a genuine storm — at 500 services that is 150 services breaching inside one
evaluation cycle — and low enough that the healthy remainder still exercises the "do not alert"
path. Flipping 100% would measure a system where every code path is the breach path, which is not a
storm so much as a different steady state.

### `COUNT = 10000` for the replay

Not a performance number. It is the evidence behind the idempotency claim, and the size is chosen so
that the answer is unambiguous: 10,000 identical events must produce exactly **1** incident and
exactly **1** breach timeline row. A run that produces 2 has found a real bug; there is no
"acceptable duplicate rate" to argue about.

All 10,000 share one `eventId` because the ID is derived from `(sloId, severity, evaluationBucket)`
and the replay holds `detectedAt` fixed.

### The `loadtest` Spring profile — production windows, not the demo's

The demo profile compresses SLO windows to 2m/5m/15m so a cascade is visible in two minutes.
**Measuring on those and quoting the result as a ceiling would be dishonest**: a 3d range selector
over a thousand services is materially more work for Prometheus than a 15m one. The `loadtest`
profile inherits the production 1h/6h/3d windows and changes only two things:

- `minimum-coverage: 0.05` — at the production 0.75, every service reports `InsufficientData` for
  the first 45 minutes while a 1h window fills, and the evaluator does no real work. That would
  measure an idle cycle and call it a ceiling.
- `SloEvaluator` logging at `ERROR` — at 500 services with 30% breaching, WARN-level breach logging
  is 300 synchronous lines per cycle *on the thread being timed*. Measurement apparatus inside the
  measurement.

### `INTERVAL = 15s` and why drift is the right metric

`@Scheduled(fixedDelay = 15s)` measures the gap **after** the previous cycle returns, so cycles can
never overlap and none is ever skipped — the schedule simply slips. "Cycles missed" is therefore
always zero and always meaningless. Drift is:

```
drift = wall_seconds − (cycles_completed × 15)
```

Zero drift means the evaluator is keeping up. Growing drift says by how much it is not, and the
*rate* of growth is the interesting part.

---

## 4. Running it

### Machine preparation — this is not optional

```bash
docker compose down -v          # the demo stack competes for the same cores
```

Give Docker room. Windows/WSL2, `%UserProfile%\.wslconfig`:

```ini
[wsl2]
memory=8GB
processors=8
swap=2GB
```

`wsl --shutdown`, restart Docker Desktop, close the IDE and browsers for the real runs.

Record the environment — a throughput number without the hardware it was taken on is not a result:

```powershell
Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors
"{0:N1} GB" -f ((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)
docker version --format '{{.Server.Version}}'; docker info --format '{{.MemTotal}}'
```

### Step 0 — smoke run. Do not skip it.

The standard way to lose two hours is to run the full ramp and find every row says `no data`.

```bash
SIZES="100" DURATION_MIN=3 ./scripts/load-test.sh     # ~8 minutes
```

Then read `docs/LOAD_TEST_RESULTS.raw.md` and check four lines:

| Line | Must be | If it isn't |
|---|---|---|
| `SLOs evaluated:` | `200` | seeding failed — everything after is meaningless |
| `Cycles completed:` | ≈ `expected` | the evaluator is not running |
| `Cycle p50/p95/p99` | a millisecond figure | histogram buckets are not publishing |
| `Query failures:` | `0` | Prometheus is unreachable or the queries are malformed |

And the subtlest bad run — every SLO returning `InsufficientData`, which produces a beautifully fast
cycle that measures nothing:

```bash
curl -s localhost:3000/actuator/prometheus | grep sentinel_slo_evaluations_total
```

`result="insufficient"` must not be ~100% of the total.

### The runs

```bash
# 1 — evaluation throughput, the ramp                              ~75 min
SIZES="100 250 500" DURATION_MIN=20 ./scripts/load-test.sh

# 2 and 3 — breach storm and alert collapse                        ~20 min
SYNTHETIC_SERVICES=500 docker compose -f docker-compose.yml -f docker-compose.loadtest.yml \
  up -d --build postgres redpanda redis prometheus grafana synthetic-exporter sentinel

# SKIP_FLEET=1 is required. The overlay deliberately does not start the eight demo-fleet JVMs,
# and the default health wait would sit through eight 300s timeouts before failing.
SKIP_FLEET=1 ./scripts/wait-for-health.sh

./scripts/measure.sh seed                    # ALWAYS first — nothing evaluates without SLOs
FRACTION=0.3 ./scripts/measure.sh storm

# 5 — duplicate replay                                             ~5 min
COUNT=10000 ./scripts/measure.sh replay

# 4 — recovery                                                     ~5 min
./scripts/measure.sh recovery

docker compose -f docker-compose.yml -f docker-compose.loadtest.yml down -v
```

`measure.sh` wraps the k6 container, refuses to run when no SLOs exist, and appends every result to
`docs/LOAD_TEST_RESULTS.raw.md` as well as printing it. Measuring into a terminal you later close
means measuring twice.

Budget **~2 hours of mostly-idle wall time**. Watch `docker stats --no-stream` during the first
step; if Prometheus stays well under 1GB at 500 services, the host had headroom for more after all.

`make` is not on the PATH in Git Bash on Windows — the script forms above are the no-make path.
`make load-test`, `make load-test-storm` and so on do the same things.

### Running these on Windows

Two traps, both of which produce confusing failures rather than clear ones.

**`bash` in PowerShell is not Git Bash.** `C:\Windows\system32\bash.exe` is the WSL launcher, and
with Docker Desktop installed the default WSL distro is `docker-desktop`, which has no `/bin/bash` —
so it fails with `execvpe(/bin/bash) failed`. Call Git Bash by full path, and use `$env:` for
variables because PowerShell has no `VAR=value cmd` prefix syntax:

```powershell
$env:SIZES="100"; $env:DURATION_MIN="3"
& "C:\Program Files\Git\bin\bash.exe" ./scripts/load-test.sh
```

Or open Git Bash directly and use the `VAR=value ./script.sh` forms above unchanged.

**MSYS path conversion silently empties docker mounts.** Git Bash rewrites anything shaped like a
POSIX path before passing it to a native Windows binary, so `-v "$(pwd)/loadtest/k6:/scripts:ro"`
arrives with the container-side `/scripts` rewritten to `C:/Program Files/Git/scripts`. k6 then
starts against an empty mount and reports a missing script rather than a bad path. Every script here
sets `export MSYS_NO_PATHCONV=1` for exactly this reason — if you run a `docker run -v` by hand,
prefix it the same way.

### Four ways to silently ruin a run

1. **Forgetting to seed.** The evaluator only evaluates SLOs that exist. An unseeded run reports a
   wonderfully fast p99 for doing nothing.
2. **Leaving the demo fleet up.** Eight JVMs and a k6 baseline generator competing for the same
   cores measures the laptop. The overlay does not start them; do not start them by hand.
3. **Reusing Prometheus data between sizes.** The previous size's series are still there and still
   being evaluated by the recording rules. `load-test.sh` does `down -v` between steps for exactly
   this reason.
4. **Running measurement 5 without the `loadtest` profile.** The replay endpoint is
   `@Profile("loadtest")` and 404s otherwise — deliberately, because an endpoint that floods the
   breach topic on demand has no business existing in a real deployment.

---

## 5. Why these parameters are defensible for an individual project

The honest calibration, because over-claiming here is worse than the smaller number.

**Where 500 sits.** Most organisations run 50–200 services, so 500 is already above the median real
fleet. Most portfolio projects in this space test with three containers and publish no numbers at
all. So 1,000 SLOs on a 15s cycle is comfortably above the bar for a personal project — and the
useful sentence is not "500" but "flat from 100 to 500, ceiling not reached, and here is why the
cost model says it stays flat".

**But the magnitude is the least impressive part, and a good reviewer knows it.**
`SYNTHETIC_SERVICES=10000` is one environment variable away. Anyone can print a large number. What
is actually hard, and what makes this defensible:

- It was **measured and published with the raw output and the hardware spec**, rather than asserted.
- The **ceiling is named honestly** — including "not reached", which is a stronger sentence than an
  invented number. Naming your own limit with evidence reads as senior; claiming unlimited scale
  reads as someone who never measured.
- **The limit that stopped the ramp is named as the test rig's, not the system's.** 500 is where an
  8 GB Docker VM on a 15.6 GB laptop ran out of room, not where cycle latency started climbing. Being
  precise about which one you hit is the difference between a measurement and a boast.
- The **methodology holds up under questioning**: drift rather than "cycles missed", p99 from
  buckets rather than a mean, production windows rather than the demo's, `down -v` between sizes,
  and a stated bucket-resolution caveat.
- Three of the five measurements are **correctness under load**, not throughput. Zero duplicate
  incidents across 10,000 replayed events is rarer in a portfolio project than a big RPS figure, and
  it opens the entire idempotency conversation.

**What to write:**

> Evaluated 1,000 SLOs across 500 synthetic service series at a 15s interval, p99 cycle latency
> ≤ X ms; correlated cascading failures at an A:B alert-collapse ratio; zero duplicate incidents
> across 10,000 replayed events.

**What not to write:** "ran a 500-service fleet", "scales to 1,000+ services" (unmeasured beyond
500), or any figure without the hardware it came from.

### The four questions this invites

**"Synthetic load isn't real load."** Correct, and deliberate. The evaluator queries time series and
has no idea what produced them — that is what the `MetricsSource` seam is for. What is being
measured is the evaluation pipeline, which is the only component whose scaling is being claimed. The
eight real fleet services exist for the cascade demo, because that is the part synthetic series
cannot tell you.

**"Why is the line flat? Are you sure it's doing work?"** Because burn rate is precomputed in
Prometheus recording rules, so the evaluator issues a **constant 30 queries per cycle** regardless
of fleet size — five distinct windows × two SLO types × three queries each. Flat is the designed
outcome, and `sentinel_slo_evaluations_total{result=...}` shows the work happening.

**"One instance isn't a scaling story."** Agreed. Horizontal scaling is `ShardAssignment`, built in
Phase 1 rather than retrofitted, with an integration test asserting the shards are disjoint and
their union complete. On Kubernetes it is one Helm flag, which renders a StatefulSet whose pods take
`SHARD_INDEX` from their ordinal. That path is built and rendered; it has not been measured under
load, and the results document says so.

**"What breaks first?"** [`SCALING.md`](SCALING.md), and the answer is not the evaluator.

---

## 6. What the measurement proves about scaling up

This is the part worth being rigorous about, because "it was fast at 500 so it will be fine at
10,000" is not an argument.

### The cost model, derived from the code rather than fitted to the data

A cycle does exactly three things:

| Component | Cost in N | Why |
|---|---|---|
| Prometheus queries | **O(1)** | 5 distinct windows × 2 SLO types × 3 queries = **30 instant queries**, issued in parallel on virtual threads. This number does not change between 100 and 100,000 services. |
| **SLO load from Postgres** | **O(N)** | `repository.findByEnabledTrue()` at the top of every cycle reads *every* enabled SLO — 2N rows — and maps each to a record before the shard filter is applied |
| Response parsing | **O(N)** | each of the 30 responses is a vector of N samples → 30N samples parsed and mapped per cycle |
| Burn-rate arithmetic | **O(N)** | 2N SLOs × 5 windows, pure in-process arithmetic, no I/O, no lock |

The Postgres read is easy to forget because it is not the interesting part of the design, but it is a
genuine per-cycle O(N) database round trip and it belongs in the slope. Two things follow that are
worth saying out loud rather than discovering later:

- **It is O(N) in the whole fleet, not in the shard.** `shardAssignment.owns(...)` filters *after*
  the fetch, so every replica in a sharded deployment reads every row on every cycle. The evaluation
  work shards cleanly; this read does not. At the measured scale it is irrelevant — thousands of
  narrow rows on a local socket — but it is the first thing to fix if the shard count ever grows,
  and the fix is a `WHERE` clause, not an architecture change.
- **It is bounded by SLO count, not series count.** Unlike parsing, it does not care how much data
  Prometheus holds, so it grows with how many things you are watching rather than how long you have
  been watching them.

So:

```
cycle(N) ≈ a + b·N
```

where `a` is the fixed query round-trip and `b` is the per-service parse-plus-arithmetic cost. The
model is **linear by construction**, and the point of the ramp is to confirm that the code does what
the model says rather than to discover the model.

**This is why the ramp is diagnostic even though it never reaches the ceiling.** If the measured
curve is linear with a small slope, the design has no hidden superlinearity. If it bends upward,
something is querying per service, and finding that is worth more than any single number. The
critical structural fact — verifiable by reading `SloEvaluator.fetchAll` — is that
`budgetCounts`, the only per-service query in the codebase, is called exclusively from the
`/slos/{id}/budget` endpoint and never from the evaluation loop.

### Extrapolating, and how far it is honest to do so

Fit the line through the three measured points and solve for the interval:

```
N_ceiling ≈ (15,000 ms − a) / b        where b is the fitted ms-per-service slope
```

Quote that as an **order of magnitude, not a figure** — "the fitted slope puts the crossover
somewhere in the tens of thousands of services, which is well outside what I measured and well
outside what I would deploy on one instance."

The extrapolation is legitimate inside the linear regime because there is no shared mutable state in
the evaluator's hot path: per-SLO evaluation is independent, fan-out is on virtual threads, and
results are collected into a map at the end. There is nothing that contends and therefore nothing to
make the curve bend.

**Three places the model stops being true, in the order they will bite:**

1. **Prometheus, not Sentinel.** The `a` term is only constant while each recording rule stays
   cheap. Rules aggregate over N services' series, so `a` itself eventually grows with N, and
   Prometheus becomes the bottleneck before the evaluator does. `SCALING.md` names this as
   bottleneck #3 and the fix — Thanos or Mimir behind the `MetricsSource` seam.
2. **Garbage collection.** Response payloads grow linearly, so at some N the per-cycle allocation
   changes GC behaviour and the curve bends upward. Not visible at 500; it is the first thing to
   look for if a larger run ever disagrees with the fitted line.
3. **Correlation, which the throughput ramp does not touch at all.** One Redis ZSET read per breach
   event becomes a hot key during a large incident — exactly when you can least afford it. That is
   bottleneck #2, and **measurement 2 (breach storm), not measurement 1, is what would find it.**
   Worth being explicit: a flat throughput curve says nothing about correlation under a storm. They
   are different measurements because they are different bottlenecks.

### The horizontal argument, which is multiplicative rather than extrapolated

Everything above concerns one instance. Beyond it, the story is not "the line stays flat further out"
— it is that the per-instance ceiling becomes a *unit*:

```
total capacity = per-instance ceiling × shardCount
```

This holds because `ShardAssignment` hashes the service name into a shard and each evaluator skips
what it does not own. Three properties make that safe rather than merely plausible, and the first
two are asserted by an integration test:

- **Disjoint and complete** — every service hashes to exactly one shard, so no SLO is evaluated
  twice and none is dropped.
- **Keyed by service, not by SLO** — a service's availability and latency SLOs land on the same
  shard, so one instance sees a service's whole picture.
- **Correlation is unaffected** — breaches are published to Kafka keyed by service name and
  correlated by the consumer, so a cascade spanning shards still collapses into one incident. The
  evaluator is sharded; the correlator is not.

What sharding does **not** give you is high availability: if shard 1 dies, its third of the fleet
stops being evaluated until Kubernetes replaces the pod. ShedLock plus a warm standby is the answer
to that, and it is a different problem from throughput — conflating them is how you end up with an
active-active design that duplicates work.

### The one-paragraph answer to "how does this scale?"

> It is designed for the scale I measured it at — 500 synthetic service series and 1,000 SLOs on a
> single instance, with p99 cycle latency two orders of magnitude below the 15s interval. I did not
> reach the ceiling, and I would rather say that than invent one. The reason it is flat is
> structural: burn rate is precomputed in recording rules, so the evaluator issues a constant 30
> queries per cycle no matter how large the fleet is, and only response parsing grows with N. The
> first thing that actually breaks is Prometheus, not the evaluator; the second is the Redis
> correlation window during a large storm. Horizontally, the fix is sharding by service hash, which
> is a config change because `ShardAssignment` was built in Phase 1 and has a test asserting the
> shards are disjoint and complete.

---

## 7. Known distortions in these numbers

Stated here rather than left for someone to find.

- **A fresh Prometheus holds minutes of history.** The `[3d]` and `[30d]` range selectors therefore
  scan far fewer samples than they would on a month-old instance, so **rule-evaluation cost is
  understated** relative to steady-state production. The direction of the error is known and
  flattering; say so before someone asks.
- **Everything shares one machine.** Prometheus, Postgres, Redpanda and Sentinel contend for the
  same cores. In production these are separate hosts, so real cycle latency would likely be *lower*
  for the same fleet size — but the contention is not modelled either way.
- **The synthetic latency distribution is static.** Two fixed bucket profiles, healthy and
  breaching. Real traffic drifts, and drift is what makes multi-window burn rate interesting.
- **No network between exporter and Prometheus.** Same host, so scrape cost excludes real network
  transfer of a ~1MB exposition document.
- **p99 is bucket-quantised.** Read every reported p99 as an upper bound.
- **The long windows are never fully populated.** On a freshly started stack even
  `minimum-coverage: 0.05` needs 18 minutes of history before a 6h window is judgeable and 3.6 hours
  before a 3d one is, so a large share of evaluations return `InsufficientData` — measured at 77.8%
  three minutes into a run, falling as the 6h window fills. **The 3d window never fills**, so MEDIUM
  severity is effectively unexercised for the whole ramp.

  This does not invalidate the cycle timings, and the reason is structural rather than a judgement
  call: `SloEvaluator.fetchAll` runs unconditionally at the top of every cycle, so all 30 queries and
  all response parsing — the terms that grow with N and dominate the measurement — happen before any
  coverage verdict is reached. What the verdict skips afterwards is per-SLO arithmetic on data
  already in memory. The reported `sentinel_slo_evaluations_total{result="insufficient"}` share is
  therefore a statement about window population, not about how much work the cycle did.
- **The sharded path is unmeasured.** The Helm chart renders it and an integration test proves the
  partitioning is correct, but no multi-instance run under load has been done.

---

## 8. Where the numbers went — completed 2026-08-15

All five measurements are taken and transcribed. This section is kept as the record of what the
process was supposed to produce, and what it actually did.

| Step | Outcome |
|---|---|
| Transcribe into [`LOAD_TEST_RESULTS.md`](LOAD_TEST_RESULTS.md) with the hardware | headline table at the top, working below |
| Delete the `STATUS: NOT YET MEASURED` banner | removed |
| State the ceiling honestly | **"ceiling not reached"** — p99 ≤ 500 ms at 4,000 series against a 15 s interval; the 8 GB rig binds near 16,000 series first |
| Ceiling at the front of [`SCALING.md`](SCALING.md) | it now opens with the measured answer, and bottleneck #2 carries what actually happened |
| Only then, the résumé bullets | CLAUDE.md §18, every figure traceable to the results file |
| Headline numbers in the README | Performance section and Known limitations, both linked to the results |

**What the ramp actually showed**, against what §6 predicted:

> Linear from 100 to 4,000 synthetic service series — `cycle_mean(N) ≈ 32.2 ms + 0.0497 ms/service`,
> R² = 0.9904 over a 40× range. **Ceiling not reached**: p99 ≤ 500 ms is 3.3% of the 15 s interval.
> Cost is dominated by the fixed query round-trip rather than by anything growing with fleet size,
> because burn rate is precomputed in recording rules. The test rig ran out of memory before the
> evaluator ran out of headroom.

Two predictions in this document were tested and one was wrong, both worth recording:

- **The cost model held.** A fit from 100/250/500 predicted 94.1 ms at 1,000 services *before* it was
  measured; the measurement returned 94 ms. It then over-predicted by ~23% at 4× and 8× beyond its
  range, which is the honest boundary of extrapolation from this rig — trustworthy to roughly double
  the measured range, degrading after.
- **The memory projections were wrong three times**, each time pessimistic: by 7× at 500 series and
  ~2× at 4,000. Memory per series is not constant (3.56 KiB/series at 42.5k, 2.61 at 333k) because a
  fixed Prometheus base was being attributed to the linear term. The *series* arithmetic was
  reliable throughout — 333,031 measured against 340,272 predicted, a 2% error.

**And the part the methodology did not anticipate at all:** the storm measurements found five
defects rather than producing a number. The throughput ramp confirmed a design; the storm found the
places it broke — an O(N²) correlation read, an auto-resolver that closed 4,243 live incidents under
consumer lag, unbounded event republishing, unbounded LLM fan-out, and a synthetic fleet with no
dependency graph at all. §6 of the results file records each with its mechanism and its
before/after. That is a better return on two hours of load testing than a p99.
