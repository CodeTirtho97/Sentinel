# Design decisions

The choices in this project that are not obvious, and the failure each one is defending against.
Every entry here is a decision where the wrong option looks correct while you are making it and only
fails later, quietly.

---

## Correlation

### The incident is keyed on the origin service, not the member set

The obvious key is `sha256(sorted(componentServices))`. It breaks the headline demo, because the
member set grows as the cascade propagates:

| Event | Breached set | `sha256(members)` | Result |
|---|---|---|---|
| ledger breaches | `{ledger}` | `a1b2…` | incident 1 opened |
| payment breaches | `{ledger, payment}` | `c3d4…` | incident 2 opened |
| cart breaches | `{ledger, payment, cart}` | `e5f6…` | incident 3 opened |
| checkout breaches | all four | `9a8b…` | incident 4 opened |

Four incidents — precisely the alert storm this project exists to collapse. And it fails *quietly*:
every individual step looks correct, the single-breach tests pass, and you only see it when you run
the full cascade.

Keying on the origin is stable under growth. `ledger-service` breaches first and stays earliest, so
all four events resolve to key `ledger-service`, attach to one incident, and widen its
`affectedServices` from 1 to 4.

**The boundary this creates**, stated rather than fixed: `correlationKey` is frozen at incident
creation. `originService` is recomputed for display, but an existing incident is never re-keyed. Two
components that later become connected therefore stay two incidents. Merging live incidents is out
of scope.

### Origin inference: earliest breach, ties broken by depth — and the tie-break does the work

A cascade through synchronous calls does not arrive politely spread over cycles. Every service in
the chain fails the instant its dependency does, so all four cross the burn threshold in the *same*
evaluation cycle.

This is why the evaluator stamps **one detection timestamp per cycle** rather than one per breach.
Stamping each breach as it is produced separates them by a few milliseconds of loop iteration,
"earliest wins" degenerates into "first in iteration order", and the platform confidently names
`checkout-service` as the origin of a failure that started in `ledger-service`.

Sharing the cycle's instant makes them genuinely tie — which is the truth, since they came from one
query snapshot — and the tie resolves on depth in the call graph, the signal that actually separates
a dependency from its callers.

**A real limit worth knowing before someone finds it.** Latency propagates additively up a
synchronous chain, so the entry point breaches first whenever the injected latency is below the
leaf's own threshold but above it once accumulated. `./scripts/demo.sh break` adds 600ms rather than 400
so `ledger-service` breaks its own 500ms objective in the same cycle as its callers. With 400ms the
platform reports `checkout-service` as the origin — and it is not malfunctioning when it does. It is
faithfully reporting which service degraded first. Earliest-plus-depth is a heuristic over a
configured topology, not causal inference.

### The subgraph is induced by the breaching set

Walking the full static dependency graph would make the demo fleet one permanent component, so any
breach anywhere would collapse into a single incident and correlation would be doing no work at all.

`BreachCorrelationIT` covers both directions: a connected cascade must produce one incident, and two
unconnected breaches must produce two. The second half is what makes the first meaningful.

---

## Idempotency

### The dedupe key is set *after* the transaction commits

Three layers absorb a duplicate delivery. They are ordered, not redundant — each catches something
the others cannot:

| Layer | Catches |
|---|---|
| Deterministic event ID | Makes a redelivery recognisable at all |
| Redis dedupe key, set after commit | The cheap common case — skips work already done |
| Partial unique index on `incident` | The commit↔mark race, and concurrent consumers on one key |

The tempting version is `SETNX` first, as a cheap guard. It silently loses events:

```
SETNX succeeds  →  DB commit fails  →  exception  →  Kafka redelivers
                →  SETNX now finds the key  →  logs DEBUG, returns
                →  the breach is gone. No incident, no DLT, no counter.
```

A dropped breach is the worst failure this system has. It is the one bug that makes the product
silently not do its job, and it produces no signal that it happened. Marking after commit leaves
only the risk of a *duplicate* delivery in the window between commit and `SET` — which is exactly
what the partial unique index absorbs.

### The event ID is deterministic, never random

`eventId` is a name-based UUID of `(sloId, severity, evaluationBucket)`, where `evaluationBucket` is
`detectedAt` truncated to the evaluation interval. A re-delivered event therefore has the same ID,
which is the precondition for the dedupe layer working at all.

`UUID.nameUUIDFromBytes` produces a **v3 (MD5)** UUID — the JDK has no v5 factory. Determinism is
what matters, so v3 is fine; it is called v3 here rather than v5 because someone will check the
version nibble.

### The partial unique index needs its predicate repeated

```sql
CREATE UNIQUE INDEX idx_active_incident
  ON incident (correlation_key)
  WHERE state != 'RESOLVED';
```

Postgres only infers a partial unique index when the statement repeats the `WHERE` clause:

```sql
INSERT INTO incident (...) VALUES (...)
ON CONFLICT (correlation_key) WHERE state != 'RESOLVED' DO NOTHING;
```

Omit the `WHERE` and Postgres looks for a *total* unique index on `correlation_key`, does not find
one, and fails the statement. Hibernate does not generate `ON CONFLICT` at all, so this is a
`@Modifying` native query with a re-read through the normal derived finder. The 50-thread
idempotency test catches a mistake here immediately.

---

## SLO evaluation

### Burn rate is computed in Prometheus, not in Java

A design decision, not an optimisation. The evaluator queries precomputed recording rules, so a
cycle is a **constant 30 instant queries** — five distinct windows × two SLO types × three queries
each — regardless of whether the fleet is 8 services or 1,000. Computing burn rate in Java would
mean one query per service per window.

Each query returns every service as a vector, indexed afterwards by the `service` label.

### `on (service) group_left` in the latency rule is mandatory

The left operand carries `{service, le}` and the right carries `{service}`. PromQL matches binary
operands on identical label sets, so without explicit matching the expression returns an **empty
vector** — no error, no warning, just silently no data.

Keeping `le` as a label means one recorded series per (service, bucket), and
`PrometheusMetricsSource` selects the bucket at query time — `slo:latency_ratio:5m{le="0.5"}` for a
500ms threshold. That keeps it one query per window rather than one rule per threshold.

### A threshold fires at exactly its value

`burn >= threshold`, so burn 1.0 is a MEDIUM breach rather than `Ok`. The two are not independently
choosable: with `>` instead, burn 14.4 would not fire CRITICAL and the headline demo stops working.

The comparison carries an epsilon — `burn >= threshold - 1e-9` — because `0.0144 / 0.001` does not
land exactly on 14.4 in IEEE 754, and an exact comparison would make the CRITICAL boundary depend on
floating-point representation error. Burn rates are order 1 to 100; 1e-9 is far below anything
meaningful.

### `slo.math` is pure Java

No Spring, no I/O, no `Instant.now()`, no randomness. It takes numbers and returns a result. It is
the only package where TDD is mandatory and the only one under mutation testing, because line
coverage there proves a burn-rate comparison executed while only a surviving `>=` → `>` mutant
proves the boundary is actually asserted.

---

## The demo profile

The `demo` profile compresses the SLO windows to 2m/1m, 5m/2m and 15m/5m, with a 2m correlation window and
a 4m auto-resolve — the production 1:2 ratio, scaled down.

**This is required for the demo to work, not a convenience.** Run the arithmetic on production
windows: 20rps baseline, 30% injected errors, `objective = 0.999`. After 60s of chaos the trailing
1h window holds 1,200 bad requests out of 72,000 → error rate 0.5% → burn rate 5.0. That is HIGH,
not CRITICAL. You do not cross 14.4 until roughly t+3min.

**There is a subtler trap behind it.** On a cold stack Prometheus holds only ~20s of history, and
`rate()` computes over the samples that exist rather than the nominal range — so the burn rate
spikes almost immediately and the demo *appears* to work. Leave the stack up for an hour and the
same injection takes minutes. The demo would work cold and degrade as it ran, which is the failure
mode where it works all week and dies on the shared screen.

Compressed windows make the timing deterministic regardless of uptime. Production defaults live in
`application.yml` and are what a real deployment runs.

### The demo makes you wait 90 seconds, on purpose

The fleet emits real 5xx while it boots, because every service is calling downstreams that are not
up yet. Those errors sit inside the 2-minute evaluation window, so the break buttons stay locked for
90s after seeding.

Measured: breaking immediately gave **3 incidents with the wrong origin**; waiting gave **1
incident, origin `ledger-service`, 94 breaches absorbed**. The wait is the difference between a demo
that works and one that looks broken.

---

## AI root cause analysis

### The fallback is a feature, not a degraded mode

The demo must work with no API key — a reviewer is not going to sign up for Groq to try the
project. With no key, `TemplateRcaDrafter` writes the same four sections from the same timeline,
deterministically, with no network call.

Framed the other way round — LLM required, degraded when absent — the observability platform would
have taken a dependency on a third party's uptime *in order to explain outages*, which is exactly
backwards. The incident is already useful without it: blast radius, inferred origin, ordered
timeline. The model adds narrative on top.

Two paths, deliberately distinct:

| Situation | What happens |
|---|---|
| No `LLM_API_KEY` | `TemplateRcaDrafter` is wired at startup and logs it. Nothing is ever called. |
| Key set, provider failing | Resilience4j retries, the circuit opens, the drafter writes the template summary. |

The first is a deliberate deviation from the obvious design, which is to let an empty key open the
circuit naturally. That does work, but it spends three retries and a timeout *per incident*
rediscovering something knowable at startup, and fills the demo's logs with provider stack traces at
precisely the moment someone is watching. The circuit breaker still earns its keep for the case it
is actually for: a key that is present and a provider that is failing.

Either way the response says which it was, because a deterministic summary and a model narrative
deserve different amounts of trust and a reader who cannot tell has no way to calibrate.

### Two traps in the resilience wiring

**`@TimeLimiter` needs a `CompletionStage`.** Applied to a method returning `String` it fails at
runtime, not compile time. `LlmChatCaller.complete` returns `CompletableFuture` for that reason, and
lives in its own bean so there is a real proxy boundary — a drafter calling its own annotated method
would bypass every annotation on it.

**Spring AI ships its own retry layer.** Left on, it multiplies with the Resilience4j retry: three
outer attempts × five inner became **15 requests** to an already-failing provider, turning a "3
attempts then fall back" policy into something far more aggressive than intended. Caught by the
WireMock test asserting exactly 3. `spring.ai.retry.max-attempts: 1` switches it off and leaves
Resilience4j owning the policy.

---

## API

### Strict validation at creation, because the alternative fails at evaluation time

- `objective` must be in `(0,1)`. At 1.0 the error budget is zero, so every burn rate is a division
  by zero — rejected with a 400 rather than discovered at 2am.
- A `LATENCY` SLO must name a `latencyThresholdMs` matching a configured histogram bucket (100, 250,
  500, 1000, 2000). A threshold with no bucket selects a label that never matches, and the query
  returns an empty vector rather than an error.
- An `AVAILABILITY` SLO must not carry a latency threshold.

### The API key is compared in constant time

`MessageDigest.isEqual`, not `String.equals`. The latter short-circuits on the first differing byte
and leaks the key a character at a time under timing analysis. The scope here is deliberately a
static shared secret with no user model, no org model and no rotation — but it should at least be
correct at the one thing it does.

---

## Kubernetes

Covered in [k8s/README.md](../k8s/README.md), but the two decisions worth naming alongside these:

**Readiness reports `OUT_OF_SERVICE`, not `DOWN`,** and the root health endpoint maps that to 200
while the readiness group keeps 503. Kubernetes pulls the pod out of the Service; nothing restarts a
container that can still serve incident history. A Prometheus outage must not become a
`CrashLoopBackOff` in the system that exists to observe outages.

**The startup probe checks liveness, not readiness.** Readiness depends on Prometheus being
reachable, and a startup probe that waits on a dependency turns a slow dependency into a crash loop.
