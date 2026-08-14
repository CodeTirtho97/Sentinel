# Sentinel

**SLO and incident intelligence for a service fleet.** Sentinel decides when services have broken
their reliability promise, and turns the resulting alert storm into a single incident with a drafted
explanation.

[![CI](https://github.com/CodeTirtho97/Sentinel/actions/workflows/ci.yml/badge.svg)](https://github.com/CodeTirtho97/Sentinel/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-orange)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green)
![Kubernetes](https://img.shields.io/badge/Kubernetes-Helm%20%2B%20kind-blue)

---

`ledger-service` degrades at 2am. Everything downstream — payments, cart, checkout — starts failing
too. The on-call engineer wakes to 60 alerts that are all the same problem and spends twenty minutes
doing correlation in their head before they can start fixing anything.

Sentinel does three things Prometheus deliberately does not:

- **Error budget accounting** — multi-window burn rate over a rolling window, so an alert means
  "you are consuming your budget too fast", not "a threshold moved"
- **Correlation** — dependency-related breaches become one incident, not N alerts
- **Lifecycle and AI RCA** — incidents move through a state machine; an LLM drafts a root-cause
  hypothesis from the correlated timeline, with a deterministic fallback when no model is configured

## Quickstart

Requires Docker Desktop. Java and Maven come from the containers.

```bash
make demo          # or: ./scripts/demo.sh   (Windows / no make)
```

Open **<http://localhost:3000>** and press **Start demo**. Seeding, breaking things and resetting
are buttons; the page shows the topology going red, incidents opening, and the alert-collapse ratio
as it happens.

| | |
|---|---|
| Demo console | <http://localhost:3000> |
| Swagger UI | <http://localhost:3000/swagger-ui.html> |
| Grafana | <http://localhost:3001> (anonymous) |
| Prometheus | <http://localhost:9090> |

`make demo` **works with no LLM API key** — root-cause drafts come from the deterministic timeline
summariser instead of a model. Set `LLM_API_KEY` for model-written narratives.

The API is behind a static key, `local-dev-key` by default:

```bash
curl -H 'X-Api-Key: local-dev-key' localhost:3000/api/v1/incidents
```

Terminal equivalents of the buttons: `make seed`, `make break`, `make break-both`, `make reset`,
`make kill`, `make watch`. Tear down with `make down`.

## Architecture

```
┌──────────────────────────────────────────────┐
│ demo-fleet — 8 instances of ONE image        │
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
                      │ 30 instant queries per cycle, any fleet size
                      ▼
      ┌──────────────────────────────────────┐
      │ sentinel-platform                    │
      │                                      │
      │   SloEvaluator ──15s──▶ MetricsSource│
      │        │ SloBreachEvent              │
      │        ▼                             │
      │   [Kafka: slo.breach.v1]             │
      │        ▼                             │
      │   BreachConsumer ─▶ CorrelationStore │
      │        │                    (Redis)  │
      │        ▼                             │
      │   IncidentService ─────▶ Postgres    │
      │        │ incident.opened.v1          │
      │        ▼                             │
      │   RcaConsumer ──▶ RcaDrafter         │
      └───────────────┬──────────────────────┘
                      ▼
                  Grafana
```

`demo-fleet` is one Spring Boot app run eight times with different environment variables. Calls
between instances are synchronous, so breaking a leaf cascades to the root.

**The two trees share no edge, deliberately.** A single connected fleet can only demonstrate the
positive case. With two, you can break a leaf in each and check Sentinel reports *two* incidents —
the half of the proof that it groups what is connected rather than what merely broke at the same
moment.

| Break | Measured result |
|---|---|
| `ledger-service` | **6 services → 1 incident**, origin `ledger-service`, 94 breaches absorbed |
| `ledger` + `catalog` | **2 incidents** — 6 services from `ledger-service`, 2 from `catalog-service` |

## How it works

### Burn rate

`errorBudget = 1 - objective`, `burnRate = errorRate / errorBudget`. A burn rate of 1.0 exhausts the
budget exactly at window end. A severity fires only when **both** its windows exceed the threshold —
the short window is what stops an alert firing long after recovery.

| Severity | Long | Short | Threshold | Budget consumed |
|---|---|---|---|---|
| `CRITICAL` | 1h | 5m | 14.4 | 2% |
| `HIGH` | 6h | 30m | 6.0 | 5% |
| `MEDIUM` | 3d | 6h | 1.0 | 10% |

Insufficient data is never a breach. Zero traffic, too few events, a partial window or an
unreachable Prometheus all return `InsufficientData` — **Sentinel failing must not manufacture
incidents.**

Burn rate is precomputed in Prometheus recording rules, so the evaluator issues a constant 30 instant
queries per cycle whether the fleet is 8 services or 500.

### Correlation

A breach enters a Redis sorted set scored by detection time. The consumer reads the last five
minutes, builds the subgraph of the dependency topology **induced by the breaching services only**,
and takes the weakly connected component containing this service as the blast radius. The origin is
the earliest breach in that component, ties broken by depth in the call chain.

The incident is keyed on the **origin service**, not a hash of the member set — the member set grows
as a cascade spreads, so hashing it produces a new incident per breach, which is exactly the alert
storm this exists to collapse.

Duplicate delivery is absorbed at three ordered layers: a deterministic event ID makes redelivery
recognisable, a Redis dedupe key set **after** commit skips work already done, and a partial unique
index on `incident` catches the commit↔mark race.

### Incident lifecycle

```
OPEN → ACKNOWLEDGED → MITIGATED → RESOLVED
OPEN → RESOLVED            (auto-resolve)
ACKNOWLEDGED → RESOLVED
```

Anything else is a 409 with an RFC 7807 body naming the allowed targets. An incident whose members
go quiet for `2 × correlation window` auto-resolves; the scheduler reads an injected `Clock`, so the
test advances time rather than sleeping.

### AI root cause analysis

`incident.opened.v1` triggers a drafter asynchronously — a ten-second model call has no business
near a fifteen-second evaluation cycle. `TimelineBuilder` assembles compact structured facts (the
incident, the induced dependency edges, the ordered breach timeline) rather than a raw dump, and the
prompt forbids inventing log lines or deploys.

With no API key, `TemplateRcaDrafter` writes the same four sections from the same timeline,
deterministically. The response always says which wrote it, because a template summary and a model
narrative deserve different amounts of trust.

## Why not just Prometheus + Alertmanager?

Recording rules compute burn rate; Alertmanager groups and inhibits. What they do not give you: a
stateful incident entity with a lifecycle, dependency-aware cross-service correlation, error budget
accounting that survives restarts, or queryable incident history for postmortems. That is the layer
PagerDuty and incident.io sell. This is a minimal open version of it.

## API

Base `/api/v1`, behind an `X-Api-Key` header compared with `MessageDigest.isEqual` rather than
`String.equals`. Actuator and Swagger sit outside the fence — Prometheus scrapes one and Kubernetes
probes it, and the other is documentation.

```
POST   /slos                             create                      201 / 400 / 409
GET    /slos                             list                        200
GET    /slos/{id}                                                    200 / 404
PATCH  /slos/{id}                        enable/disable, retarget    200 / 404
DELETE /slos/{id}                                                    204 / 404
GET    /slos/{id}/budget                 burn rate + budget left     200 / 404

GET    /incidents?state=&severity=&since=&page=&size=                200
GET    /incidents/{id}                   detail + timeline           200 / 404
POST   /incidents/{id}/transition        {"to":"ACKNOWLEDGED"}       200 / 404 / 409
GET    /incidents/{id}/rca               drafted hypothesis          200 / 202 / 404
POST   /incidents/{id}/rca:regenerate    redraft                     202 / 404

GET    /services                         fleet + dependency edges    200
```

`GET /rca` answers **202 while a draft is still being written**, so a poller can tell "not yet" from
"here it is" without inspecting the body. Errors are RFC 7807 `ProblemDetail`.

Every fleet instance also exposes chaos injection on its own port — `api-gateway` 8081 through
`catalog-service` 8088, in the order listed in `docker-compose.yml`:

```bash
curl -X POST 'localhost:8086/chaos/errors?rate=0.3'   # ledger-service: 30% of requests return 500
curl -X POST 'localhost:8086/chaos/latency?ms=600'
./scripts/reset-chaos.sh                              # reset the whole fleet
```

## Testing

```bash
make test-unit     # unit tests, no Docker, ~15s
make test          # + Testcontainers suite (real Postgres, Redpanda, Redis)
make mutation      # PIT on slo.math, threshold 85%
make coverage      # JaCoCo, gate at line ≥75% / branch ≥65%
```

Real containers, because mocked brokers hide serialization and rebalance bugs. The suite is weighted
towards failure paths rather than happy paths:

| Scenario | Assertion |
|---|---|
| Cascade | four connected breaches in-window → **one** incident, origin `ledger-service` |
| No false correlation | two unconnected breaches → **two** incidents |
| Idempotency | 50 threads on one key → exactly one incident, one opener |
| Redelivery | 50 deliveries of one event → one timeline entry |
| Dead letter | malformed payload → DLT, next valid message still processes |
| Consumer restart | backlog released after restart → nothing lost, nothing duplicated |
| Auto-resolve | injected clock advanced past the quiet period → RESOLVED, no sleeping |
| Sharding | two shards evaluate disjoint service sets whose union is complete |
| LLM failures | 500s, 429s and empty 200s → retries, then the deterministic summary, no dead letter |
| API key | missing, wrong and prefix-of-correct keys all 401 |
| Kubernetes probes | unready without partition assignment; a dead metrics source leaves liveness at 200 |

CI runs formatting, unit tests, mutation testing, the Testcontainers suite behind the coverage gate,
a Docker build from a clean checkout, and a Helm lint plus schema validation of the rendered chart.

## Kubernetes

Compose is the primary path; Kubernetes is a working second target.

```bash
make kind-demo     # cluster, images, helm install — same URLs as Compose
make kind-drain    # delete the pod mid-incident; nothing lost, nothing duplicated
```

Readiness is tied to **Kafka partition assignment** and metrics-source reachability, not just an open
port — a replica with no partitions serves the API perfectly while consuming nothing. Graceful drain
means `preStop`, 30s of Spring shutdown, and manual acks, so an evicted pod's in-flight message is
reprocessed rather than lost. Sharding is one Helm flag. Details, and an honest list of what the
deployment is *not*, in [k8s/README.md](k8s/README.md).

## Performance

Measured on a single instance (`shardCount=1`), Intel Core Ultra 5 125H, Docker VM limited to
8 GB / 8 cores. Full method and raw output in
**[`docs/LOAD_TEST_RESULTS.md`](docs/LOAD_TEST_RESULTS.md)**.

| | Measured |
|---|---|
| **Alert collapse** | **5.00 : 1** — 2,000 failing services → **400 incidents**, mean correlated component size 5.00 |
| **Scale** | **8,000 SLOs across 4,000 synthetic service series**, evaluated every 15s |
| **Cycle latency** | **p99 ≤ 500 ms** — 3.3% of the interval, **30× headroom**; ceiling not reached |
| **Query cost** | **constant 30 instant queries**, independent of fleet size |
| **Detection** | 123–138s from injection to incident, against 115s predicted from the burn-rate maths |
| **Idempotency** | 10,000 duplicate events → **exactly 1 incident**, 1 timeline row, 0 dead-letters |
| **Recovery** | **40s** from `docker kill` mid-cycle to evaluating again, zero duplicate incidents |
| **Under storm** | 50% of the fleet breaching: consumer lag bounded 182–356, memory 31–33% of the VM |

Cycle cost is linear in fleet size with a shallow slope — `32.2 ms + 0.0497 ms/service`,
R² = 0.9904 across a 40× range. **The test rig ran out of memory at ~16,000 series before the
evaluator ran out of interval budget**, so the ceiling quoted here is the laptop's, not the
system's.

**The load test is also where five real defects were found** — including a correlation read that was
quadratic in storm size, and an auto-resolver that silently closed 4,243 live incidents while their
services were still failing. Each is written up with its mechanism, fix and re-measured result in
[§6 of the results](docs/LOAD_TEST_RESULTS.md). Why each parameter has the value it does is in
[`docs/BENCHMARK_METHODOLOGY.md`](docs/BENCHMARK_METHODOLOGY.md).

## Known limitations

Stated deliberately rather than discovered later.

- **Time-window plus static-topology correlation, not causal inference.** It answers "these broke
  together and they are connected", not "this caused that". The dependency graph is configured.
- **Incidents never merge once opened.** `correlationKey` is frozen at creation, so two components
  that later connect stay two incidents.
- **A cascade can briefly open more than one incident** when the ends of a chain trip a cycle before
  its middle. The transient one auto-resolves once it stops receiving breaches.
- **Correlation is a hot key** — one Redis read per breach event, the wrong shape during a large
  incident. Measured: at 2,000 concurrently breaching services this put the consumer 18,310 messages
  behind before the window was reduced to service names. The `CorrelationStore` seam exists so it can
  move to a Kafka Streams state store at the next order of magnitude.
- **Readiness reports UP ~2.4s before the dependency graph is seeded.** `DependencySeeder` is an
  `ApplicationRunner`, so it runs after the web server starts answering. A breach arriving in that
  window correlates against an empty graph. Bounded to one startup window and does not corrupt later
  incidents, but the graph belongs in the readiness contract.
- **Recovery takes as long as the SLO window, not as long as the fix.** Burn rate is computed over a
  rolling window, so a service that stops failing keeps its incident open until the bad samples age
  out — roughly an hour on the production 1h window.
- **Single tenant, single evaluator instance.** Sharding is built and rendered by the Helm chart but
  has not been run under load.
- **`make demo` uses compressed SLO windows** so a cascade is visible in two minutes. Production
  defaults are the standard 1h/6h/3d.
- **The RCA is a hypothesis.** The model sees the incident, the induced edges and the timeline — no
  logs, no deploys, no config history.
- **The API key is a static shared secret, not an IAM.** No users, no orgs, no rotation.

## Project layout

```
sentinel-platform/     the product — SLO evaluation, correlation, incidents, RCA
demo-fleet/            one Spring Boot app, run as eight services
synthetic-exporter/    N fake service series, for load testing only
infra/                 Prometheus rules, provisioned Grafana dashboards
k8s/                   Helm chart and kind cluster
loadtest/              k6 scripts
docs/                  scaling analysis, benchmark methodology, design decisions
```

**Five seams**, the only abstractions in the project, each because it is a plausible swap:
`MetricsSource` (Thanos, Mimir), `EventPublisher` (in-memory for tests), `CorrelationStore` (Kafka
Streams), `RcaDrafter` (template fallback), `IncidentRepository`. Two rules keep them real: nothing
outside `slo.metrics` knows PromQL exists, and `slo.math` is pure Java — no Spring, no I/O, no
`Instant.now()` — which is why it is the only package under mutation testing.

## Documentation

| | |
|---|---|
| **[Load test results](docs/LOAD_TEST_RESULTS.md)** | **all five measurements, the hardware they were taken on, and the five defects the storm exposed** |
| [Design decisions](docs/DESIGN_DECISIONS.md) | why the incident is keyed on origin, why the dedupe key is set after commit, why the subgraph is induced, and the traps behind each |
| [Scaling](docs/SCALING.md) | back-of-envelope load, what breaks first and in what order, the fix for each |
| [Benchmark methodology](docs/BENCHMARK_METHODOLOGY.md) | the five measurements, why each parameter has its value, what a flat curve proves |
| [Kubernetes](k8s/README.md) | probes, graceful drain, sharding, and what the deployment is not |

## Stack

Java 21 (virtual threads, records, sealed interfaces) · Spring Boot 3.5 · PostgreSQL + Flyway ·
Kafka (Redpanda locally) · Redis · Prometheus + Grafana · Resilience4j · Spring AI · Testcontainers ·
WireMock · k6 · Helm + kind
