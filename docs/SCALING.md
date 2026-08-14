# Scaling

This document exists so that "how does this scale?" gets answered with a number and a named
bottleneck rather than a hand-wave.

## The measured answer

> **8,000 SLOs across 4,000 synthetic service series on a single instance, evaluated every 15
> seconds at p99 ≤ 500 ms — 3.3% of the interval.** I did not reach the evaluator's ceiling and
> would rather say so than invent one: the 8 GB test rig ran out of memory near 16,000 series while
> cycle latency was still using a thirtieth of its budget.
>
> Cost is linear and shallow — `32.2 ms + 0.0497 ms/service`, R² = 0.9904 across a 40× range —
> because burn rate is precomputed in Prometheus recording rules, so a cycle issues a **constant 30
> instant queries** whatever the fleet size. Only response parsing grows with N.
>
> **The first thing that actually broke was not the evaluator.** It was bottleneck #2 below, the
> Redis correlation window, and it broke exactly as this document predicted it would: during a large
> incident. At 2,000 concurrently breaching services the consumer fell 18,310 messages behind, and
> the knock-on was worse than the slowness — stale data drove the auto-resolver to close 4,243 live
> incidents. Fixed by storing service names rather than serialized events in the window and
> announcing state changes rather than republishing every cycle; re-measured at lag 182–356 and a
> 5:1 alert-collapse ratio. See [§6 of the results](LOAD_TEST_RESULTS.md).
>
> Beyond one instance the answer is sharding by service hash, which is a config change because
> `ShardAssignment` was built in Phase 1 and has a test asserting the shards are disjoint and
> complete. That path is rendered by the Helm chart and unit-tested, but has not been run under load.

Measured figures live in [LOAD_TEST_RESULTS.md](LOAD_TEST_RESULTS.md). Everything below is the
reasoning around them — written before the measurements, and left as written except where the
results contradicted it.

---

## Back of the envelope

The load per evaluation cycle is smaller than it looks, and the reason is §5.3: **burn rate is
precomputed in Prometheus recording rules, not in the evaluator.**

A naive implementation queries Prometheus once per SLO per window. At 100 services × 2 SLOs × 2
windows that is 400 queries every 15 seconds, and at 1000 services it is 4000 — enough that
Prometheus, not Sentinel, becomes the story.

Because the recording rules aggregate `by (service)`, one instant query returns **every service as
a vector**. `SloEvaluator.fetchAll` deduplicates the work down to one fetch per distinct
`(SLO type, latency threshold, window)` — not per SLO, and certainly not per service:

| Dimension | Distinct values | Why |
|---|---|---|
| Windows | 5 | `5m`, `30m`, `1h`, `6h`, `3d` — the three severities' long and short windows, with `6h` shared between HIGH-long and MEDIUM-short |
| SLO types | 2 | `AVAILABILITY` and `LATENCY` |
| Queries per fetch | 3 | the ratio series, `slo:requests:{window}` (minimum-events guard), `slo:samples:{window}` (coverage guard) |

**A constant 30 instant queries per cycle regardless of fleet size**, issued in parallel on virtual
threads. Adding the thousandth service adds a row to a vector that was already being fetched, not a
query.

The one per-service query in the codebase is `budgetCounts`, and it is called exclusively from the
`GET /slos/{id}/budget` endpoint — never from the evaluation loop. That separation is what makes the
cost model linear, and it is worth re-checking if the measured curve ever bends upward.

The rest of the per-cycle work is in-process: for each SLO, a map lookup and the burn-rate
arithmetic from `slo.math`, which is pure and allocation-light. The fan-out runs on virtual threads,
so the concurrency is nearly free.

Downstream of detection, volumes are low by construction. Kafka carries one event per breach per
cycle, and a fleet that is mostly healthy produces almost none. Postgres sees a handful of incident
writes per hour — an incident is opened once and then widened, not rewritten. Redis holds a
5-minute hot set that expires by TTL.

Single instance is genuinely fine well past 500 services. The load test asked exactly how far past,
and the answer was **further than the rig could go**: 4,000 series at 3.3% of the interval budget,
with the 8 GB VM binding near 16,000 before cycle latency became the constraint.

The prediction in this section held. What it under-estimated was the *downstream* cost — see
bottleneck #2, where the evaluator stayed comfortable while everything behind it fell over.

---

## What breaks first, in order

### 1. The single-instance `@Scheduled` evaluator

The binding constraint, and the reason `ShardAssignment` exists from Phase 1 rather than being
retrofitted.

The naive way to scale a scheduled job is to run more replicas, and here that is actively wrong:
every replica would evaluate every SLO, publish its own breach event for the same detection, and
triple-count the burn rate. Deterministic event IDs mean the duplicates would at least be
*recognised* — the same `(sloId, severity, bucket)` produces the same UUID — so the dedupe layers
would absorb them. But the system would be doing three times the work to reach the same answer, and
the DLQ would be the only place that hinted at it.

**Symptom:** `sentinel.slo.cycle.duration` p99 climbing toward the 15s interval, and interval drift
growing.

Note that `@Scheduled(fixedDelay = 15s)` measures the gap *after* the previous cycle returns, so
cycles can never overlap and none is ever skipped. A slow cycle does not pile up; the schedule
slips. That is why the load test reports **drift** — actual wall time minus (cycles × 15s) — rather
than "cycles missed", which would always be zero and would tell you nothing.

### 2. The Redis correlation window

Every breach event does a ZADD and a ZRANGEBYSCORE against one key, `breaches:recent`. That key is
shared by the whole fleet, so it is hottest during a large incident — exactly when the system can
least afford it.

**Symptom:** Redis command latency rising in step with breach volume, visible as consumer lag on
`slo.breach.v1` while the evaluator itself is still comfortable.

> **Measured, and this is the one that actually bit.** At 2,000 concurrently breaching services the
> consumer fell **18,310 messages behind** and never recovered, while the evaluator sat at a fifth
> of its interval — precisely the asymmetry described above.
>
> The cost was worse than predicted, because the window held *serialized events* rather than service
> names. Each event therefore deserialized every event in the window: roughly **320 million JSON
> parses per cycle**, quadratic in the size of the storm. Worse still, the backlog froze
> `lastBreachAt`, and the auto-resolver read the staleness as recovery and **closed 4,243 incidents
> whose services were still failing** — a silent all-clear during the exact event the product
> exists for.
>
> Fixed without moving to Kafka Streams: the window now stores service names keyed by earliest
> breach, and the evaluator announces state changes plus a two-minute heartbeat instead of
> republishing every cycle. Events per run fell 543,600 → 32,000 and peak lag 18,310 → 182–356.
> **The Kafka Streams migration remains the answer at the next order of magnitude**; it was simply
> not what this bottleneck needed.

### 3. Prometheus itself

A single Prometheus is a retention and query bottleneck long before the evaluator is. At 500
synthetic services — ~42k active series — the recording rules are evaluating range selectors over
that series count every 15 seconds, and the 3d and 30d rules are expensive enough that they are
deliberately on slower groups.

**Symptom:** `sentinel.metrics.query.duration` rising while `sentinel.slo.evaluation.duration`
stays flat — the evaluator waiting rather than working.

### 4. The connected-component walk

`DependencyGraph.componentOf` is O(V+E) over the subgraph induced by currently-breached services,
run once per breach event. Fine at hundreds of services. At 10,000 with a dense graph, a storm that
breaches most of the fleet turns each event into a walk over most of the fleet — quadratic in the
size of the storm.

**Symptom:** `BreachConsumer` throughput falling as the number of *simultaneously breaching*
services rises, while a single breach stays fast.

---

> The measurement side of this argument — the cost model the ramp is designed to confirm, and how
> far it is honest to extrapolate from it — is in
> [BENCHMARK_METHODOLOGY.md](BENCHMARK_METHODOLOGY.md) §6.

## The fixes, in the order you would do them

| Bottleneck | Fix | Cost |
|---|---|---|
| Evaluator throughput | Run N replicas with distinct `SHARD_INDEX` / `SHARD_COUNT` | Config change — already built |
| Evaluator HA, not throughput | ShedLock: one active, others warm standby | Small |
| Redis hot key | Move `CorrelationStore` to a Kafka Streams windowed state store, partitioned by correlation key | Moderate — the seam exists |
| Component walk | Cache the precomputed component map, invalidate on topology change | Small |
| Prometheus | Thanos or Mimir behind `MetricsSource` | Moderate — the seam exists |

### Sharding, concretely

`ShardAssignment` hashes the service name into a shard and the evaluator skips SLOs it does not
own:

```java
public boolean owns(String serviceName) {
    if (shardCount <= 1) return true;
    return Math.floorMod(serviceName.hashCode(), shardCount) == shardIndex;
}
```

At the default `0/1` one instance owns everything. Scaling out is running N replicas with distinct
indices:

```yaml
sentinel-0: { SHARD_INDEX: "0", SHARD_COUNT: "3" }
sentinel-1: { SHARD_INDEX: "1", SHARD_COUNT: "3" }
sentinel-2: { SHARD_INDEX: "2", SHARD_COUNT: "3" }
```

On Kubernetes that is one flag:

```bash
helm upgrade sentinel ./k8s/helm/sentinel -n sentinel \
  --set sentinel.sharding.enabled=true --set sentinel.sharding.shardCount=3
```

which renders a StatefulSet instead of a Deployment and has each pod read `SHARD_INDEX` out of its
own ordinal. A Deployment cannot do this — its pods are interchangeable and their names are random —
and that is the only reason a component with no per-pod state is a StatefulSet. See
[`k8s/README.md`](../k8s/README.md).

Three properties make this safe rather than merely plausible:

- **Disjoint and complete.** Every service hashes to exactly one shard, so no SLO is evaluated
  twice and none is dropped. There is an integration test asserting both halves of that.
- **Keying by service, not by SLO.** A service's availability and latency SLOs land on the same
  shard, so one instance sees a service's whole picture.
- **Correlation is unaffected.** Breaches are published to Kafka keyed by service name and
  correlated by the consumer, so a cascade spanning shards still collapses into one incident. The
  evaluator is sharded; the correlator is not.

What sharding does *not* give you is high availability. If shard 1 dies, its third of the fleet
stops being evaluated. ShedLock plus a standby is the answer to that, and it is a different problem
from throughput — worth keeping separate, because conflating them is how you end up with an
active-active design that duplicates work.

---

## What is deliberately not solved

Stating the boundary is worth more than pretending there isn't one.

- **Correlation is time-window plus static topology, not causal inference.** The dependency graph is
  configured, not discovered. Origin inference is "earliest breach in the component, ties broken by
  depth" — a heuristic that is right often enough to be useful and is never presented as more.
- **Incidents never merge once opened.** `correlationKey` is frozen at creation. Two components that
  later become connected stay two incidents. Merging live incidents is out of scope, and the
  alternative — re-keying — is what splits one incident into one-per-breach.
- **Single tenant.** No org or team model anywhere in the schema.
- **One Prometheus, one Redis, one Postgres.** Each is a single point of failure. The seams exist so
  that changing them is a swap rather than a rewrite, but none of that work has been done.
- **The 30d error-budget rules are expensive.** They run on a 5-minute group for that reason. At a
  large enough fleet they are the first Prometheus rule group to fall behind, and the budget gauge
  goes stale before anything else does.
