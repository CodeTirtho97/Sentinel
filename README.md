# Sentinel — SLO & Incident Intelligence Platform

Sentinel watches a fleet of services, decides when they have broken their reliability promise, and
turns the resulting alert storm into a single incident with a drafted explanation.

> **Status: all four phases complete.** SLOs are evaluated, breaches flow through Kafka, a
> dependency-related cascade collapses into a single durable incident with a lifecycle, and an LLM
> drafts a root-cause hypothesis from the correlated timeline — falling back to a deterministic
> summary when no model is configured, so `make demo` needs no API key. Kubernetes is a working
> second deployment target: `make kind-demo`, with readiness tied to Kafka partition assignment and
> a graceful drain that loses nothing ([k8s/README.md](k8s/README.md)). **Compose stays the primary
> path.**
>
> The load-test harness is built and runnable, but **the measurements have not been taken**:
> [`docs/LOAD_TEST_RESULTS.md`](docs/LOAD_TEST_RESULTS.md) is a template with placeholders, not
> results. Any performance claim in this README is reasoning from the design, and is labelled as
> such.
>
> See [Build phases](#build-phases). The full spec is in [CLAUDE.md](CLAUDE.md).

---

## The problem

A company runs 40 services. `ledger-service` degrades at 2am. Everything downstream of it — payments,
cart, checkout — starts failing too. The on-call engineer wakes up to 60 alerts that are all the same
problem, and spends the first twenty minutes doing correlation in their head before they can start
fixing anything.

Sentinel does three things Prometheus deliberately does not:

1. **Error budget accounting** — evaluates SLOs using multi-window burn rate over a rolling window
2. **Correlation** — groups dependency-related breaches into a single incident instead of N alerts
3. **Lifecycle + AI RCA** — incidents move through a state machine; an LLM drafts a root-cause
   hypothesis from the correlated timeline

All three are built.

## Quickstart

Requires **Docker Desktop running**, and nothing else — Java and Maven come from the containers.

```bash
make demo             # or: ./scripts/demo.sh   (Windows / no make)
```

Then open **<http://localhost:3000>** and press **Start demo**. Seeding, breaking things
and resetting are buttons; the page shows the topology going red, the incidents opening, and the
alert-collapse ratio as it happens.

| | |
|---|---|
| **Demo console** | **<http://localhost:3000>** — the front door; everything else is a link from here |
| Swagger UI | <http://localhost:3000/swagger-ui.html> |
| Grafana | <http://localhost:3001> (anonymous, no login) |
| Prometheus | <http://localhost:9090> |
| Sentinel API | <http://localhost:3000/api/v1/incidents> (needs `X-Api-Key`) |

**`make demo` works with no LLM API key.** With none configured, root-cause drafts come from the
deterministic timeline summariser instead of a model — same four sections, no signup, no network
call. Set `LLM_API_KEY` to get model-written narratives. That is the design, not a limitation: see
[AI root cause analysis](#ai-root-cause-analysis).

Everything under `/api/v1` is behind a static API key, defaulting to `local-dev-key`. In Swagger
press **Authorize**; from a terminal pass the header:

```bash
curl -H 'X-Api-Key: local-dev-key' localhost:3000/api/v1/incidents
```

Prefer a terminal? `make seed`, `make break`, `make break-both`, `make reset`, `make kill`,
`make watch` do exactly what the buttons do.

The page is built for demoing, not just for watching:

| | |
|---|---|
| **Elapsed ribbon** | failure injected → first breach → incident open → root cause drafted, as offsets from t=0 |
| **Alerts collapsed** | the ratio, beside the actual pages you did not get and the one incident you did |
| **Root cause** | read from `GET /incidents/{id}/rca`, badged with the model that wrote it — or `deterministic fallback — no model` |
| **Kill Sentinel** | halts the process mid-incident; Docker restarts it and the page reports recovery time and whether anything duplicated |
| **Why these choices** | ten design decisions with what each is defending against — the correlation key, the dedupe ordering, the induced subgraph, the optional LLM |
| **Presenter / Freeze** | larger type for a projector; stop polling so the screen holds still while you talk |

Measured on this machine: **19s** from `docker kill` to serving again, same incident, no duplicates.

**One thing the demo will make you wait for, on purpose.** The fleet emits real 5xx while it boots,
because every service is calling downstreams that are not up yet. Those errors sit inside the
2-minute evaluation window, so the break buttons stay locked for 90s after seeding. Measured:
breaking immediately gave **3 incidents with the wrong origin**; waiting gave **1 incident, origin
`ledger-service`, 94 breaches absorbed**. The wait is the difference between a demo that works and
one that looks broken.

Watch breaches arrive, then watch them collapse into one incident:

```bash
docker compose logs -f sentinel | grep -E 'BREACH|OPENED|ATTACHED'

curl -s -H 'X-Api-Key: local-dev-key' localhost:3000/api/v1/incidents | jq
# → ONE incident, "originService": "ledger-service", four affected services

# ...and the drafted hypothesis for it
curl -s -H 'X-Api-Key: local-dev-key' localhost:3000/api/v1/incidents/<id>/rca | jq -r .draft
```

Tear down with `make down`.

<details>
<summary>Running the steps by hand</summary>

```bash
export SPRING_PROFILES_ACTIVE=demo
docker compose up -d --build      # ~3-5 min on first run
./scripts/wait-for-health.sh      # blocks until all 7 containers are healthy
./scripts/seed-slos.sh            # 8 SLOs: availability + latency per service
docker compose up -d loadgen      # ~20 rps against checkout-service
sleep 20
./scripts/inject-cascade.sh       # break ledger-service
```
</details>

## Architecture

```
┌──────────────────────────────────────────────┐
│ demo-fleet (8 instances, ONE image)          │
│                                              │
│ ORDER PATH                                   │
│  api-gateway → checkout → cart ─┐            │
│                   └→ payment ←──┘            │
│                        └→ fraud → ledger     │
│                                              │
│ BROWSE PATH  (no edge to the order path)     │
│  search → catalog                            │
└───────────────────┬──────────────────────────┘
                    │ /actuator/prometheus
                    ▼
      ┌──────────────────────────────┐
      │ Prometheus                   │
      │ + recording rules            │
      │   (burn rate precomputed)    │
      └───────────────┬──────────────┘
                      │ a few instant queries per cycle
                      ▼
      ┌──────────────────────────────────────┐
      │ sentinel-platform                    │
      │                                      │
      │   SloEvaluator ──15s──▶ MetricsSource│
      │        │                             │
      │        │ SloBreachEvent              │
      │        ▼                             │
      │   [Kafka: slo.breach.v1]             │
      │        │                             │
      │        ▼                             │
      │   BreachConsumer ─▶ CorrelationStore │
      │        │                    (Redis)  │
      │        ▼                             │
      │   IncidentService ─────▶ Postgres    │
      │        │                             │
      │        │ incident.opened.v1          │
      │        ▼                             │
      │   RcaConsumer ──▶ RcaDrafter         │
      │                   (Spring AI, with a │
      │                    template fallback)│
      └───────────────┬──────────────────────┘
                      ▼
                  Grafana
```

`demo-fleet` is **one** Spring Boot app run eight times with different environment variables, not
eight projects. The calls between instances are synchronous, so breaking the leaf of a chain makes
the failure cascade to the root — which is the whole point of the demo.

The two trees share no edge, and that is deliberate. A single connected fleet can only ever
demonstrate the positive case. With two, you can break a leaf in each and check that Sentinel
reports **two** incidents rather than one — which is the half of the proof that it is grouping what
is *connected* rather than merely what is broken at the same moment.

| Break | Measured result |
|---|---|
| `ledger-service` | **6 services → 1 incident**, origin `ledger-service`, 94 breaches absorbed |
| `ledger` + `catalog` | **2 incidents**: 6 services from `ledger-service`, 2 from `catalog-service`, 140 breaches absorbed |

## How the SLO math works

- `errorBudget = 1 - objective` — at 99.9%, 0.1% of requests may fail
- `burnRate = errorRate / errorBudget` — burn rate 1.0 exhausts the budget exactly at window end

A severity fires only when **both** its long and short windows exceed the threshold. The short window
is what stops an alert firing long after the service recovered.

| Severity | Long | Short | Threshold | Budget consumed |
|---|---|---|---|---|
| `CRITICAL` | 1h | 5m | 14.4 | 2% |
| `HIGH` | 6h | 30m | 6.0 | 5% |
| `MEDIUM` | 3d | 6h | 1.0 | 10% |

Insufficient data is never a breach. Zero traffic, too few events, a partially covered window, or an
unreachable Prometheus all return `InsufficientData`. **Sentinel failing must not manufacture
incidents.**

### The demo profile

`make demo` runs with `SPRING_PROFILES_ACTIVE=demo`, which compresses the SLO windows to 2m/1m, 5m/2m
and 15m/5m, and the correlation settings alongside them — a 2m correlation window and a 4m
auto-resolve, keeping the production 1:2 ratio. On the production 1h window the first CRITICAL breach
lands about three minutes after chaos starts, which is not a demo anyone can sit through; on the
production 10m auto-resolve, recovery lands well after everyone has stopped watching.

There is a subtler reason. On a freshly started stack Prometheus has only seconds of history, and
`rate()` computes over the samples that exist rather than the nominal range — so burn rate spikes
immediately and the demo *appears* to work. Leave the stack up for an hour and the same injection
takes minutes. The demo would work cold and degrade as it ran. Compressed windows make the timing
deterministic instead. Production defaults are in
[`application.yml`](sentinel-platform/src/main/resources/application.yml).

## Why burn rate is computed in Prometheus

The evaluator queries precomputed recording rules, not raw metrics. At 1000 services that is the
difference between 4000 queries per cycle and a handful. `PrometheusMetricsSource` issues one instant
query per window returning **every** service as a vector, then indexes by the `service` label.

Rules live in
[`infra/prometheus/rules/slo-recording-rules.yml`](infra/prometheus/rules/slo-recording-rules.yml).

## The five seams

The only abstractions in the project. Each exists because it is a plausible swap.

| Interface | Default | Swappable to | Status |
|---|---|---|---|
| `MetricsSource` | `PrometheusMetricsSource` | Thanos, Mimir, Datadog | **built** |
| `EventPublisher` | `KafkaEventPublisher` | `InMemoryEventPublisher`, for tests | **built** |
| `CorrelationStore` | `RedisCorrelationStore` | Kafka Streams state store | **built** |
| `RcaDrafter` | `SpringAiRcaDrafter` | `TemplateRcaDrafter` fallback | **built** |
| `IncidentRepository` | Spring Data JPA | anything | **built** |

Two rules keep the seams real:

1. **Nothing outside `slo.metrics` knows PromQL exists.** No Prometheus response type, no query
   string, no HTTP concept crosses `MetricsSource`.
2. **`slo.math` is pure Java.** No Spring, no I/O, no `Instant.now()`, no randomness. It is the only
   package where TDD is mandatory and the only one under mutation testing (`make mutation`).

## How correlation works

A breach arrives. The consumer pushes it into a Redis sorted set scored by detection time, reads
everything from the last five minutes, and builds the subgraph of the dependency topology **induced
by the breaching services only**. The weakly connected component containing this service is the
incident's blast radius. The origin is the earliest breach in that component, ties broken by depth in
the call chain.

Three decisions here are load-bearing, and each has a failure mode that is silent if you get it wrong.

**Origin inference reads "earliest breach, ties broken by depth" — and the tie-break does the real
work.** A cascade through synchronous calls does not arrive politely spread over cycles: every
service in the chain fails the instant its dependency does, so all four cross the burn threshold in
the same evaluation cycle. That is why the evaluator stamps one detection timestamp per cycle rather
than one per breach. Stamping each breach as it is produced separates them by a few milliseconds of
loop iteration, "earliest wins" degenerates into "first in iteration order", and the platform
confidently names `checkout-service` as the origin of a failure that started in `ledger-service`.
Sharing the cycle's instant makes them genuinely tie — which is the truth, since they came from one
query snapshot — and the tie resolves on depth in the call graph, the signal that actually separates
a dependency from its callers.

There is a real limit here worth knowing before someone finds it. Latency propagates additively up a
synchronous chain, so the **entry point breaches first** whenever the injected latency is below the
leaf's own threshold but above it once accumulated. `inject-cascade.sh` adds 600 ms rather than 400,
so `ledger-service` breaks its own 500 ms objective in the same cycle as its callers instead of a
cycle later. With 400 ms the platform reports `checkout-service` as the origin, and it is not
malfunctioning when it does — it is faithfully reporting which service degraded first. Earliest-plus-
depth is a heuristic over a configured topology, not causal inference.

**The incident is keyed on the origin service, not on a hash of the member set.** The obvious key is
`sha256(sorted(component))`. It breaks the headline demo, because the member set grows as the cascade
spreads: `{ledger}`, then `{ledger,payment}`, then `{ledger,payment,cart}` — a different hash each
time, so four breaches produce four incidents. That is precisely the alert storm this project exists
to collapse, and every individual step looks correct while it happens. Keying on the origin is stable
under growth: ledger breaches first, stays earliest, and all four events resolve to the same key.

**The subgraph is induced by the breaching set.** Walking the full static graph would make the demo
fleet one permanent component, so any breach anywhere would collapse into a single incident and
correlation would be doing no work at all. `BreachCorrelationIT` covers both directions: a connected
cascade must produce one incident, and two unconnected breaches must produce two.

**Duplicate delivery is absorbed at three layers, in order.** They are not redundant — each catches
something the others cannot:

| Layer | Catches |
|---|---|
| Deterministic event id | Makes a redelivery recognisable at all |
| Redis dedupe key, set **after** commit | The cheap common case — skips work already done |
| Partial unique index on `incident` | The commit↔mark race, and concurrent consumers on one key |

The ordering of the second one matters more than it looks. Marking the event processed *before* the
transaction commits is faster and silently loses events: mark, commit fails, Kafka redelivers, the
mark is found, the consumer logs DEBUG and returns — and the breach is gone, with no incident, no
dead letter, and no counter to notice it by.

## Incident lifecycle

```
OPEN → ACKNOWLEDGED → MITIGATED → RESOLVED
OPEN → RESOLVED            (auto-resolve)
ACKNOWLEDGED → RESOLVED
```

Anything else is a 409 with an RFC 7807 body naming the allowed targets. The transition table lives
in the `IncidentState` enum, and all sixteen `(from, to)` pairs are enumerated in one test — including
the eleven illegal ones, because a table that accidentally grows an entry is invisible to a test that
only checks the legal ones.

An incident whose members have gone quiet for `2 × correlation window` auto-resolves. The scheduler
reads an injected `Clock`, so the test advances time rather than sleeping for ten minutes.

## AI root cause analysis

When an incident opens, `incident.opened.v1` triggers a drafter that turns the correlated timeline
into a hypothesis. It is asynchronous by design: a ten-second model call has no business anywhere
near a fifteen-second evaluation cycle, and the evaluator never learns it happened.

`TimelineBuilder` assembles compact structured facts rather than a raw dump — the incident, the
dependency edges **induced by the affected services only**, and the ordered breach timeline:

```
INCIDENT:
  opened_at: 2026-08-06T02:14:31Z
  severity: CRITICAL
  affected: cart-service, checkout-service, ledger-service, payment-service
  inferred_origin: ledger-service

DEPENDENCY EDGES:
  checkout-service -> cart-service
  cart-service -> payment-service
  payment-service -> ledger-service

BREACH TIMELINE:
  02:14:31  ledger-service     AVAILABILITY  burn=22.1
  02:14:46  payment-service    LATENCY       burn=17.3
  02:15:01  cart-service       LATENCY       burn=9.2
  02:15:01  checkout-service   AVAILABILITY  burn=15.5
```

The prompt is a contract: use only the data provided, never invent log lines or deploys, say so
when the data is insufficient, four fixed sections, 200 words, and it is a hypothesis for a human
to verify rather than a conclusion.

### The fallback is a feature

**`make demo` must work with no API key** — a reviewer is not going to sign up for Groq to try your
project. With no key configured, `TemplateRcaDrafter` writes the same four sections from the same
timeline, deterministically, with no network call and a line saying plainly that no model was
involved.

Framed the other way round — LLM required, degraded when absent — the observability platform would
have taken a dependency on a third party's uptime *in order to explain outages*, which is exactly
backwards. The incident is already useful: it has a blast radius, an inferred origin, and an
ordered timeline. The model adds narrative on top of that.

Two paths, deliberately distinct:

| Situation | What happens |
|---|---|
| No `LLM_API_KEY` | `TemplateRcaDrafter` is wired at startup and logs it. Nothing is ever called. |
| Key set, provider failing | Resilience4j retries, then the circuit opens; the drafter catches it and writes the template summary. |

The first case is a deviation from the spec worth naming: §8 suggests letting an empty key open the
circuit naturally. It does work, but it spends three retries and a timeout *per incident*
rediscovering something already knowable at startup, and fills the demo's logs with provider stack
traces at precisely the moment someone is watching. The circuit breaker still earns its keep for
the case it is actually for — a key that is present and a provider that is failing.

Either way the response says which it was, because a deterministic summary and a model narrative
deserve different amounts of trust and a reader who cannot tell has no way to calibrate:

```json
{ "status": "READY", "model": "template", "fallback": true, "draft": "SUMMARY
..." }
```

### Two traps worth naming

**`@TimeLimiter` needs a `CompletionStage`.** Applied to a method returning `String` it fails at
runtime, not compile time. `LlmChatCaller.complete` returns `CompletableFuture` for that reason,
and lives in its own bean so there is a real proxy boundary — a drafter calling its own annotated
method would bypass every annotation on it.

**Spring AI ships its own retry layer.** Left on, it multiplies with the Resilience4j retry: three
outer attempts times five inner became **15 requests** to an already-failing provider. Caught by
the WireMock test asserting exactly 3. `spring.ai.retry.max-attempts: 1` switches it off and leaves
Resilience4j owning the policy.

## API

Base `/api/v1`, behind a static `X-Api-Key` header. Actuator and Swagger sit outside the fence:
Prometheus scrapes the first and Kubernetes probes it, and the second is documentation.

Explore it at **<http://localhost:3000/swagger-ui.html>** — press *Authorize*, paste
`local-dev-key`.

This is deliberately not an IAM. There is no user model, no org model, and no token lifecycle
anywhere in this project, and building one would be more machinery standing in for a decision
nobody made. What it does have to be is correct at the one thing it does, so the comparison is
`MessageDigest.isEqual`, not `String.equals` — the latter short-circuits on the first differing
byte and leaks the key a character at a time under timing analysis.

```
POST   /slos                       create                      201 / 400 / 409
GET    /slos                       list                        200
GET    /slos/{id}                                              200 / 404
PATCH  /slos/{id}                  enable/disable, retarget    200 / 404
DELETE /slos/{id}                                              204 / 404
GET    /slos/{id}/budget           burn rate + budget left     200 / 404

GET    /incidents?state=&severity=&since=&page=&size=          200
GET    /incidents/{id}             detail + timeline           200 / 404
POST   /incidents/{id}/transition  {"to":"ACKNOWLEDGED"}       200 / 404 / 409
GET    /incidents/{id}/rca         drafted hypothesis          200 / 202 / 404
POST   /incidents/{id}/rca:regenerate  redraft, discards old   202 / 404

GET    /services                   fleet + dependency edges    200
```

`GET /rca` answers **202 while a draft is still being written** and 200 once there is something to
read, so a poller can tell "not yet" from "here it is" without inspecting the body — which matters
because the draft lands seconds after the incident does.

Errors are RFC 7807 `ProblemDetail`, built into Spring Boot 3.

```bash
curl -X POST localhost:3000/api/v1/slos \
  -H 'Content-Type: application/json' \
  -d '{"serviceName":"checkout-service","type":"AVAILABILITY","objective":0.999,"rollingWindow":"P30D"}'
```

Validation that is deliberately strict:

- `objective` must be in `(0,1)` — 1.0 leaves a zero error budget, so it is a 400, not a runtime
  division by zero
- a `LATENCY` SLO must name a `latencyThresholdMs` matching a configured histogram bucket
  (100, 250, 500, 1000, 2000) — a threshold with no bucket cannot be evaluated at all
- an `AVAILABILITY` SLO must not carry a latency threshold

## Chaos endpoints

Every fleet instance exposes failure injection. Ports: checkout `8081`, cart `8082`,
payment `8083`, ledger `8084`.

```bash
curl -X POST 'localhost:8084/chaos/errors?rate=0.3'    # 30% of requests return 500
curl -X POST 'localhost:8084/chaos/latency?ms=400'     # add 400ms
curl -X POST 'localhost:8084/chaos/hang'               # stop responding
curl -X POST 'localhost:8084/chaos/reset'
./scripts/reset-chaos.sh                               # reset all four
```

## Development

```bash
make test-unit           # unit tests, no Docker needed, ~15s
make test                # + Testcontainers integration suite (real Postgres/Redpanda/Redis)
make mutation            # PIT on slo.math, threshold 85%
make coverage            # JaCoCo, gate at line >=75% / branch >=65%
./mvnw -q -DskipTests package
./mvnw spotless:apply    # format
```

CI runs all of it: [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — spotless and unit tests
first for fast feedback, then mutation testing, the Testcontainers suite behind the coverage gate,
and a job that builds all three Docker images from a clean checkout, because "a stranger can clone
and run `make demo`" is a claim worth actually checking.

Maven comes from the wrapper — you only need a JDK 21 on the host, and not even that if you work
through Docker.

Integration tests are named `*IT` and run under failsafe, so `mvn test` stays fast and Docker-free
while `mvn verify` runs the real thing. The build pins the Docker API version Testcontainers
negotiates to `1.41`: its default of `1.32` is below the `1.40` floor that Docker Engine 29 enforces,
and the resulting HTTP 400 surfaces as the thoroughly misleading *"Could not find a valid Docker
environment"*.

### What the integration suite actually proves

Real containers, because mocked brokers hide serialization and rebalance bugs.

| Scenario | Assertion |
|---|---|
| Happy path | one breach → one incident with the right severity, origin and blast radius |
| Cascade | four connected breaches in-window → **one** incident, origin `ledger-service` |
| Simultaneous cascade | four breaches in **one cycle**, sharing a timestamp → one incident, origin still `ledger-service`, and the answer does not change when arrival order is reversed |
| No false correlation | two unconnected breaches → **two** incidents |
| Idempotency | 50 threads on one key → exactly one incident, exactly one opener |
| Redelivery | 50 deliveries of one event → one timeline entry, `breachCount` of 1 |
| Resolved keys free up | a resolved incident does not block a new one under the same key |
| Dead letter | malformed payload → DLT, and the next valid message still processes |
| Auto-resolve | clock advanced past the quiet period → RESOLVED, no sleeping |
| Consumer restart | backlog released after restart → nothing lost, nothing duplicated |
| Sharding | two shards evaluate disjoint service sets whose union is complete |
| RCA drafting | an opened incident acquires a draft on its own, with no key configured |
| LLM 500s | 3 retries, then the deterministic summary — and **no** dead letter |
| LLM 429 / empty 200 | both treated as failures; the incident still gets a draft |
| Circuit breaker | after repeated failures the provider stops being called at all |
| Regeneration | `rca:regenerate` replaces a draft; a plain request does not |
| API key | missing, wrong, and prefix-of-correct keys all 401; Actuator and OpenAPI stay open |
| Demo seeding | a month of history, all RESOLVED, leaving the active-incident index free |
| Kubernetes probes | consumers ready only once partitions are assigned; an unreachable metrics source makes the pod unready but leaves liveness and the root health endpoint at 200 |

## Build phases

| Phase | Goal | Status |
|---|---|---|
| 1 — Foundation | metrics flow, SLOs evaluate, breaches detected | **done** |
| 2 — Event-driven core | Kafka + Redis + Postgres, breaches become one correlated incident | **done** |
| 3 — Intelligence & proof | AI RCA, API key + Swagger, demo seeding, load-test harness | **done**, except the measurements |
| 4 — Kubernetes | Helm + kind, probes, graceful shutdown | **done** |

Phase 3 shipped the RCA pipeline, the static API key and Swagger UI, 30 days of seeded history, the
`synthetic-exporter` module, the k6 load-test harness, CI, and
[`docs/SCALING.md`](docs/SCALING.md).

Phase 4 shipped the Helm chart, the `kind` cluster, Actuator-backed probes, graceful drain, KEDA
lag-based scaling, and Prometheus pod discovery — see [Kubernetes](#kubernetes) below.

**What Phase 3 did not ship is the numbers.** The harness runs, but
[`docs/LOAD_TEST_RESULTS.md`](docs/LOAD_TEST_RESULTS.md) is still a template — those runs need hours
on a quiet machine, and a benchmark taken on a laptop that was also doing something else is worse
than no benchmark. Until it has real numbers, no throughput or ceiling claim appears in this README
or on a résumé.

The methodology is written down regardless:
[`docs/BENCHMARK_METHODOLOGY.md`](docs/BENCHMARK_METHODOLOGY.md) covers what each of the five
measurements is evidence for, why each parameter has the value it does, how to run the ramp, and —
the part that matters most — what a flat curve on one instance does and does not prove about
scaling up.

**Measured on the demo profile**, clean stack, chaos injected into `ledger-service` at 35% errors and
600ms added latency:

| | |
|---|---|
| Chaos injected → first `BREACH CRITICAL` | ~25–45s |
| → incident open in the API | **~30s** |
| Raw breaches absorbed by the incident | 377 |
| **Open** incidents during the cascade | **1**, origin `ledger-service`, all four affected |
| `docker kill sentinel` → evaluation resumed | ~35s, state preserved, **0** duplicate incidents |
| `reset-chaos.sh` → everything auto-RESOLVED | ~9.5 min |

The detection lag is the `rate()` pipeline, not the evaluator — a 2m range selector scraped every 15s
with recording rules evaluated every 15s. Shorter windows detect faster and flap more; that is the
trade the multi-window design exists to make.

Recovery is slower than detection for the same reason, and the auto-resolve setting is not what
dominates it: after the chaos stops, the error data is still inside the trailing windows, so breaches
keep firing for several minutes before the 4m quiet period can even start counting.

**One caveat on that "1", stated plainly.** A cascade does not always trip every service in the same
cycle. `checkout-service` is the entry point and every failed request passes through it, so its error
ratio moves fastest; `ledger-service` breaches its latency objective immediately. Those two often trip
a cycle before `cart` and `payment` — and they are the two ends of the chain, not adjacent. At that
instant they are genuinely two disconnected components and correctly become two incidents. A cycle
later the middle of the chain breaches, everything connects, and the cascade collapses into the
`ledger-service` incident as intended.

The short-lived extra incident then stops receiving breaches and **auto-resolves itself**, so
`GET /incidents?state=OPEN` settles at exactly one. Measured: the transient incident resolved 4
minutes after its last breach while the real one was still absorbing traffic. Collapsing the two at
the moment they connect would need live incident merging, which is deliberately out of scope (§7).

## Known limitations

Stated deliberately rather than discovered later.

- **This is time-window plus static-topology correlation, not causal inference.** It answers "these
  broke together and they are connected", not "this caused that".
- **The dependency graph is configured, not discovered.** It lives in `application.yml`.
- **Incidents never merge once opened.** `correlationKey` is frozen at creation, so two components
  that later become connected stay two incidents. This has a visible consequence worth naming: a
  cascade is collapsed correctly when breaches are *processed* in roughly the order they were
  detected, which is what happens live, because each service breaches on its own evaluation cycle.
  Breaches are keyed by service name and therefore spread across partitions, so a large backlog
  released at once — after a consumer restart, say — has no defined cross-service order. Processing
  `checkout-service` before `ledger-service` legitimately opens a checkout incident that ledger's can
  never absorb. `ConsumerRestartIT` covers that case and asserts what does hold regardless of
  ordering: every breach recorded exactly once, no service unaccounted for. Merging live incidents
  would fix it and is deliberately out of scope.
- **Correlation is a hot key.** One Redis ZSET read per breach event, which is exactly the wrong
  shape during a large incident. The `CorrelationStore` seam exists so this can move to a Kafka
  Streams windowed state store when it needs to.
- **Coverage is a proxy for window completeness.** A window is judged from its scrape-sample count, not
  from when a service genuinely started.
- **Single tenant, single evaluator instance.** Sharding is built in (`ShardAssignment`) and the
  Helm chart renders a sharded StatefulSet on one flag, but a multi-replica deployment has not been
  run under load — there is no measured reason to yet, and the ceiling that would give one is the
  unmeasured number above.
- **A cascade can briefly open more than one incident.** When the ends of a call chain trip a cycle
  before its middle, they are disconnected at that moment and correctly become separate incidents;
  the transient one auto-resolves once it stops receiving breaches. Measured above. This is the same
  frozen-key boundary as the previous point, seen from the other side.
- **`make demo` uses compressed SLO windows.** Explained above; production defaults are the standard
  1h/6h/3d.
- **The RCA is a hypothesis, and the fallback says so.** The model sees only the incident, the
  induced dependency edges and the breach timeline — no logs, no deploys, no config history — so it
  cannot identify a cause the correlated data does not already contain. The prompt forbids inventing
  any, and the response reports whether a model or the deterministic summariser wrote it.
- **The RCA is drafted once, when the incident opens.** A cascade that widens afterwards does not
  redraft itself; the blast radius in a draft can legitimately be narrower than the incident's
  final one. `POST /incidents/{id}/rca:regenerate` redraws it on demand.
- **The API key is a static shared secret, not an IAM.** No users, no orgs, no rotation, no
  expiry. Compared in constant time, and that is the whole of it.
- **The load-test numbers do not exist yet.** The harness is built and documented; the runs have not
  been done. [`docs/LOAD_TEST_RESULTS.md`](docs/LOAD_TEST_RESULTS.md) is a template, and is labelled
  as one rather than filled with plausible-looking figures.

## Why not just Prometheus + Alertmanager?

Recording rules compute burn rate and Alertmanager groups and inhibits alerts. What they do not give
you: a stateful incident entity with a lifecycle, dependency-aware cross-service correlation, error
budget accounting that survives restarts, or queryable incident history for postmortems. That is the
layer PagerDuty and incident.io sell. This is a minimal open version of it.

## Scaling

[`docs/SCALING.md`](docs/SCALING.md) covers the back-of-envelope load per cycle, what breaks first
and in what order, and the fix for each — with the honest framing that the first bottleneck is the
single-instance evaluator and the fix is a config change, because `ShardAssignment` was built in
Phase 1 rather than retrofitted.

The number that belongs at the front of that document is the measured ceiling, and it is not
measured yet.

## Kubernetes

**Compose is the primary path** and nothing here is needed for `make demo`. Kubernetes is a second,
real deployment target: the same images, expressed as a Helm chart on a local `kind` cluster.

```bash
make kind-demo     # cluster, images, helm install — then http://localhost:3000, same as Compose
                   # or: ./scripts/kind-demo.sh   (Windows / no make)
make kind-status   # pods, and what each probe currently says
make kind-drain    # delete the sentinel pod mid-incident; nothing lost, nothing duplicated
make helm-lint     # lint and render the chart; needs no cluster
```

It exists because three of this project's claims only become testable under an orchestrator.

**Readiness that means something.** Two custom indicators back the readiness group: a replica with
no Kafka partition assignment is unready, and so is one that cannot reach Prometheus. A pod in
either state serves the API perfectly while doing none of the actual work, and no default probe can
see that. They report `OUT_OF_SERVICE` rather than `DOWN` — Kubernetes pulls the pod out of the
Service, and nothing restarts a container that can still serve incident history. The startup probe
deliberately checks *liveness*, because a startup probe that waits on a dependency turns a slow
dependency into a crash loop.

**What happens to an in-flight message when a pod is evicted.** `preStop` sleeps 5s so kube-proxy
finishes removing the pod before `SIGTERM`; Spring gets 30s to drain; anything unfinished never had
its offset committed, because the consumers ack manually after the database transaction commits. The
group rebalances, another pod reprocesses, and reprocessing is a no-op — deterministic event ID,
Redis dedupe, partial unique index. `make kind-drain` asserts it: no incident lost, no correlation
key with two active incidents.

**Scaling, with the awkward part stated.** `replicas: 1`, and the template says why at length: two
replicas at `shardCount=1` both evaluate every SLO. `--set sentinel.sharding.enabled=true` renders a
StatefulSet instead, each pod taking `SHARD_INDEX` from its ordinal — a values change rather than a
code change, because `ShardAssignment` was built in Phase 1. The KEDA `ScaledObject` scales on
consumer lag rather than CPU (during a breach storm the consumers are waiting on I/O, so CPU barely
moves) and **ships disabled**, because one deployable holds both the evaluator and the consumers, so
scaling on lag also scales the evaluator. Splitting the two roles is written down as future work
rather than papered over.

Prometheus discovers targets by pod annotation instead of a static list. One deliberate omission in
the relabeling: the `service` label is not derived from any Kubernetes object — it comes from the
application, because that label is the domain identity the recording rules aggregate by and the
correlation key of an incident.

Full detail, including a candid list of what this deployment is *not* (no managed cluster ops, no
mesh, no operators, demo-grade backing services): **[k8s/README.md](k8s/README.md)**.
