# Sentinel — SLO & Incident Intelligence Platform

**Build spec for Claude Code.** Read this file completely before writing any code. Build in phase order. Do not begin a phase until the previous one runs and its tests pass.

---

## 0. Orientation

### The one-sentence pitch

Sentinel watches a fleet of services, decides when they have broken their reliability promise, and turns the resulting alert storm into a single incident with a drafted explanation.

### The problem it solves

A company runs 40 services. `ledger-service` degrades at 2am. Everything downstream of it — payments, cart, checkout — starts failing too. The on-call engineer wakes up to 60 alerts that are all the same problem, and spends the first twenty minutes doing correlation in their head before they can start fixing anything.

Sentinel does three things Prometheus deliberately does not:

1. **Error budget accounting** — evaluates SLOs using multi-window burn rate over a rolling window
2. **Correlation** — groups dependency-related breaches into a single `Incident` instead of N alerts
3. **Lifecycle + AI RCA** — incidents move through a state machine; an LLM drafts a root-cause hypothesis from the correlated timeline

### Design priorities, in order

1. **Demoable** — a stranger sees the value in under two minutes without reading docs
2. **Provable** — every scaling and performance claim has a measured number behind it
3. **Extensible** — five named seams; swapping any one does not touch the others
4. **Correct under failure** — idempotent, durable, degrades gracefully
5. Feature count — dead last. Scope discipline matters more than features.

### Deployment strategy

**Docker Compose is the primary path.** The README quickstart is `make demo`. Kubernetes is a real, working second deployment target in `k8s/`, built in Phase 4 — not a toy, but not the thing a reviewer has to install `kind` for.

### Explicit non-goals

Do not build these. If a feature is not named in a phase, it does not exist.

- No alert delivery (Slack, PagerDuty, email)
- No multi-tenancy, no org/team model
- No user management UI or full IAM — a static API key header
- No auto-remediation or runbook execution
- No user-supplied PromQL — fixed templates only
- No React frontend. Grafana + Swagger are the UI.
- No service mesh, no operators, no CRDs
- No cloud deployment. `kind` locally only.

---

## 1. Architecture

```
┌───────────────────────────────────────────────────────────┐
│ demo-fleet (4 instances)      synthetic-exporter          │
│ checkout → cart → payment     1000 fake service series    │
│                  → ledger     (load testing only)         │
└─────────────────┬─────────────────────┬───────────────────┘
                  │ /actuator/prometheus │ /metrics
                  ▼                     ▼
          ┌──────────────────────────────────┐
          │ Prometheus                       │
          │ + recording rules (burn rate     │
          │   precomputed per service)       │
          └───────────────┬──────────────────┘
                          │ single range query per cycle
                          ▼
    ┌───────────────────────────────────────────────┐
    │ sentinel-platform                             │
    │                                               │
    │  SloEvaluator  ──15s──▶ MetricsSource         │
    │       │                                       │
    │       │ SloBreachEvent                        │
    │       ▼                                       │
    │  [Kafka: slo.breach.v1]                       │
    │       │                                       │
    │       ▼                                       │
    │  BreachConsumer ──▶ CorrelationStore (Redis)  │
    │       │                                       │
    │       ▼                                       │
    │  IncidentService ──▶ Postgres                 │
    │       │                                       │
    │       │ incident.opened.v1                    │
    │       ▼                                       │
    │  RcaConsumer ──▶ RcaDrafter (Spring AI)       │
    └───────────────────────────────────────────────┘
                          │
                          ▼
                 Grafana (2 dashboards)
```

---

## 2. Tech stack — every dependency justified

If you add something not on this list, put a justification comment in the POM.

| Tool | Justification |
|---|---|
| **Java 21** | Virtual threads make the evaluator's parallel fan-out cheap without a reactive rewrite. Records for events, sealed interfaces for results, pattern matching for the state machine. |
| **Spring Boot 3.3+** | One deployable holds the scheduler, REST API, and Kafka consumers. Actuator gives Prometheus metrics and K8s probes for free. |
| **Spring Data JPA + PostgreSQL** | Incidents are relational and need ACID. Concurrent breach events must not create duplicate incidents — enforced by a partial unique index. |
| **Flyway** | Schema evolves across four phases. `ddl-auto: validate` so drift fails loudly. |
| **Kafka** (Redpanda locally) | Detection, correlation, and LLM RCA have wildly different latency profiles. A 10s LLM call must never block a 15s evaluation cycle. Keying by service preserves per-service ordering. |
| **Spring Kafka** | `@KafkaListener`, `DefaultErrorHandler`, `DeadLetterPublishingRecoverer` — DLQ without hand-rolling. |
| **Redis** | Correlation needs a sliding window read on every event and expired by TTL. Postgres is wrong for a 5-minute hot set. |
| **Prometheus** | The metrics source. Also scrapes Sentinel itself — we are both consumer and subject. |
| **Micrometer + Actuator** | Instruments Sentinel; Spring-native path to Prometheus; backs K8s probes. |
| **Grafana** | Two provisioned dashboards. JSON in the repo so they exist on first boot with zero clicking. |
| **Resilience4j** | Circuit breaker on the LLM, retry on Prometheus. The observability platform must not become the outage. |
| **Spring AI** | Keeps LLM integration in Java. No Python sidecar — the point is a Java-forward repo. |
| **Testcontainers** | Real Postgres, Kafka, Redis in tests. Mocked brokers hide serialization and rebalance bugs. |
| **WireMock** | Deterministic, offline stubs for the Prometheus API and the LLM. |
| **Awaitility** | Async assertions without `Thread.sleep()`. |
| **k6** | Load testing. Scriptable, containerized, JSON output for the README. |
| **JaCoCo + PIT** | Coverage gate in CI; mutation testing on the SLO math package specifically. |
| **springdoc-openapi** | Swagger UI so the API is explorable without Postman. |
| **Helm + kind** (Phase 4) | Kubernetes deployment without cloud spend. |

**Local Kafka:** use **Redpanda** — one container, Kafka-API compatible, no ZooKeeper, ~200MB. Code uses the standard Kafka client, so "Kafka" is an honest claim.

---

## 3. The five seams

These are the only abstractions in the project. Do not add a sixth. Each exists because it is a plausible swap, not because indirection feels tidy.

| Interface | Package | Default impl | Swappable to | Why it's a seam |
|---|---|---|---|---|
| `MetricsSource` | `slo.metrics` | `PrometheusMetricsSource` | Thanos, Mimir, Datadog | Metrics backend changes at scale |
| `EventPublisher` | `events` | `KafkaEventPublisher` | `InMemoryEventPublisher` (tests) | Lets integration tests run without a broker |
| `CorrelationStore` | `correlation` | `RedisCorrelationStore` | Kafka Streams state store | The documented scale fix |
| `RcaDrafter` | `rca` | `SpringAiRcaDrafter` | `TemplateRcaDrafter` (fallback) | LLM must be optional |
| `IncidentRepository` | `incident` | Spring Data JPA | anything | Standard |

### Two rules that make the seams real

**Rule 1: Nothing outside `slo.metrics` knows PromQL exists.**

```java
public interface MetricsSource {
    /** Returns the ratio of bad events to total events over the window. */
    Optional<ErrorRatio> errorRatio(String serviceName, SloType type,
                                    Integer latencyThresholdMs, Duration window);

    /** Health of the backing store — feeds the readiness probe. */
    boolean isHealthy();
}

public record ErrorRatio(double ratio, long totalEvents, Instant asOf) {}
```

`Optional.empty()` means insufficient data — never a breach. No Prometheus response type, no PromQL string, no HTTP concept crosses this boundary.

**Rule 2: `slo.math` is pure Java.**

No Spring, no I/O, no `Instant.now()`, no randomness. Takes numbers, returns a result. It is the only package where TDD is mandatory and the only one under mutation testing.

---

## 4. Repository layout

```
sentinel/
├── pom.xml                       # parent, dependencyManagement
├── Makefile                      # demo, test, load-test, kind-up
├── docker-compose.yml            # PRIMARY deployment path
├── README.md
├── .github/workflows/ci.yml
│
├── docs/
│   ├── architecture.png
│   ├── demo.gif
│   ├── LOAD_TEST_RESULTS.md      # measured numbers + hardware spec
│   └── SCALING.md                # the ceiling analysis
│
├── infra/
│   ├── prometheus/
│   │   ├── prometheus.yml
│   │   └── rules/slo-recording-rules.yml   # burn rate precomputed HERE
│   └── grafana/provisioning/
│       ├── datasources/prometheus.yml
│       └── dashboards/
│           ├── dashboards.yml
│           ├── fleet-slo.json
│           └── sentinel-internals.json
│
├── k8s/                          # PHASE 4 — secondary path
│   ├── README.md
│   ├── kind-cluster.yaml
│   └── helm/sentinel/
│       ├── Chart.yaml
│       ├── values.yaml
│       └── templates/
│
├── loadtest/
│   ├── k6/
│   │   ├── evaluation-throughput.js
│   │   ├── breach-storm.js
│   │   └── duplicate-replay.js
│   └── README.md
│
├── sentinel-platform/            # THE product
│   ├── pom.xml
│   └── src/main/java/com/sentinel/
│       ├── SentinelApplication.java
│       ├── config/               # Kafka, Redis, Resilience, Security, OpenApi, Sharding
│       ├── slo/
│       │   ├── domain/           # SloDefinition, SloType, Severity, Window
│       │   ├── math/             # PURE — BurnRateCalculator, ErrorBudgetCalculator
│       │   ├── metrics/          # MetricsSource + PrometheusMetricsSource + PromQlTemplates
│       │   ├── SloEvaluator.java # @Scheduled, shard-aware
│       │   ├── ShardAssignment.java
│       │   └── SloDefinitionService / Repository / Controller
│       ├── events/               # SloBreachEvent, IncidentEvent, Topics, EventPublisher
│       ├── correlation/          # CorrelationStore, DependencyGraph, BreachConsumer
│       ├── incident/             # Incident, IncidentState, IncidentService, Controller
│       ├── rca/                  # RcaDrafter, TimelineBuilder, RcaConsumer
│       ├── observability/        # MeterConfig, KafkaReadinessIndicator
│       └── seed/                 # DemoDataSeeder — 30 days of synthetic history
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/         # V1__slo.sql … V4__seed.sql
│       └── prompts/rca-system.st
│
├── demo-fleet/                   # ONE app, 4 env-configured instances
│   └── src/main/java/com/sentinel/fleet/
│       ├── FleetApplication.java
│       ├── WorkController.java
│       ├── ChaosController.java
│       ├── ChaosState.java
│       └── DependencyCaller.java
│
└── synthetic-exporter/           # LOAD TESTING ONLY
    └── src/main/java/com/sentinel/synth/
        ├── SyntheticExporterApplication.java
        └── SeriesGenerator.java   # N fake services, configurable error/latency
```

**Two critical structural decisions:**

- `demo-fleet` is ONE Spring Boot app run four times with different env vars. Do not write four projects.
- `synthetic-exporter` exists so load tests do not require 500 containers. It exposes N fake service series that Prometheus scrapes. **This is what makes the resume numbers measurable on a laptop.**

### Fleet topology

```
checkout-service ──▶ cart-service ──┐
       │                            ├──▶ payment-service ──▶ ledger-service
       └────────────────────────────┘
```

Breaking `ledger-service` cascades upward and must produce **one** incident naming `ledger-service` as origin.

---

## 5. Domain model

### 5.1 SLO definition

```java
public record SloDefinition(
    UUID id,
    String serviceName,
    SloType type,                 // AVAILABILITY | LATENCY
    double objective,             // 0.999
    Integer latencyThresholdMs,   // null for AVAILABILITY; must match a histogram bucket
    Duration rollingWindow,       // P30D — error budget window
    boolean enabled
) {}
```

Persist as `SloDefinitionEntity`; keep the record as the domain and API type.

### 5.2 Burn rate math

Pure package. Most heavily tested code in the repo.

- `errorBudget = 1 - objective`
- `burnRate = errorRate / errorBudget`

Burn rate 1.0 exhausts the budget exactly at window end. 14.4 burns 2% of a 30-day budget in one hour.

**Multi-window, multi-burn-rate** (Google SRE Workbook Ch. 5). Fire only when BOTH windows exceed the threshold — the short window stops alerts firing long after recovery.

| Severity | Long | Short | Threshold | Budget consumed |
|---|---|---|---|---|
| `CRITICAL` | 1h | 5m | 14.4 | 2% |
| `HIGH` | 6h | 30m | 6.0 | 5% |
| `MEDIUM` | 3d | 6h | 1.0 | 10% |

```java
public sealed interface BurnRateResult {
    record Ok(double longBurn, double shortBurn) implements BurnRateResult {}
    record Breach(Severity severity, double longBurn, double shortBurn) implements BurnRateResult {}
    record InsufficientData(String reason) implements BurnRateResult {}
}
```

**Edge cases that MUST be handled and tested:**
- Zero total events → `InsufficientData`. Never divide by zero, never alert.
- `objective == 1.0` → zero error budget → reject at creation with HTTP 400
- Partial window (service started 10min ago, 1h window) → `InsufficientData`
- `NaN` or empty from the metrics source → `InsufficientData`, not a breach
- Multiple severities trip → return the **highest** only

**Error budget remaining**, computed independently over the rolling window:
```
budgetRemaining = 1 - (errorsInWindow / (totalInWindow * errorBudget))
```
Clamp to `[0,1]`. Expose as a gauge.

This needs error and total counts over the **full 30-day window**, which the burn-rate
recording rules (§5.3, max window 3d) do not provide. Add a separate 30d recording rule
at a coarse interval — see `slo_budget` in §5.3. Without it this gauge has no data source
and the budget panel renders empty.

### 5.3 Recording rules — burn rate computed in Prometheus

**This is a design decision, not an optimization.** The evaluator queries precomputed series, not raw metrics. At 1000 services this is the difference between 4000 queries per cycle and one.

`infra/prometheus/rules/slo-recording-rules.yml`:

```yaml
groups:
  - name: slo_burn_rate
    interval: 15s
    rules:
      - record: slo:error_ratio:5m
        expr: |
          sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
          / sum by (service) (rate(http_server_requests_seconds_count[5m]))
      - record: slo:error_ratio:1h
        expr: |
          sum by (service) (rate(http_server_requests_seconds_count{status=~"5.."}[1h]))
          / sum by (service) (rate(http_server_requests_seconds_count[1h]))
      # repeat for 30m, 6h, 3d
      # latency variants use histogram buckets:
      - record: slo:latency_ratio:5m
        expr: |
          1 - (
            sum by (service, le) (rate(http_server_requests_seconds_bucket[5m]))
            / on (service) group_left
            sum by (service) (rate(http_server_requests_seconds_count[5m]))
          )

  # Error budget accounting — separate group, coarse interval.
  # 30d range queries are expensive; do NOT put these on the 15s group.
  - name: slo_budget
    interval: 5m
    rules:
      - record: slo:errors:30d
        expr: sum by (service) (increase(http_server_requests_seconds_count{status=~"5.."}[30d]))
      - record: slo:total:30d
        expr: sum by (service) (increase(http_server_requests_seconds_count[30d]))
```

**The `on (service) group_left` in the latency rule is mandatory, not stylistic.** The left
operand carries `{service, le}` and the right carries `{service}`. PromQL matches binary
operands on identical label sets, so without explicit matching the expression returns an
**empty vector** — no error, no warning, just silently no data. Any ratio rule that mixes
`le` with a non-`le` aggregate needs this.

Keeping `le` as a label means one recorded series per (service, bucket).
`PrometheusMetricsSource` selects the bucket at query time — `slo:latency_ratio:5m{le="0.5"}`
for a 500ms threshold — so this stays **one** instant query per window returning all services
as a vector, then indexed by the `service` label. Not one query per service, and not one rule
per threshold.

Configure `demo-fleet` Micrometer with explicit buckets: `100ms, 250ms, 500ms, 1s, 2s`. Validate `latencyThresholdMs` against these at SLO creation; reject with 400 otherwise.

### 5.4 Shard-aware evaluator

Build this in from Phase 1. Ten lines now; makes horizontal scaling a config change rather than a rewrite.

```java
@Component
public class ShardAssignment {
    private final int shardIndex;   // default 0
    private final int shardCount;   // default 1

    public boolean owns(String serviceName) {
        if (shardCount <= 1) return true;
        return Math.floorMod(serviceName.hashCode(), shardCount) == shardIndex;
    }
}
```

`SloEvaluator` filters by `shardAssignment.owns(slo.serviceName())` before evaluating. At `0/1` it owns everything. Document in `SCALING.md` that scaling out means running N replicas with distinct `shardIndex`.

### 5.5 Incident

```java
public enum IncidentState { OPEN, ACKNOWLEDGED, MITIGATED, RESOLVED }
```

Legal transitions ONLY:
```
OPEN → ACKNOWLEDGED → MITIGATED → RESOLVED
OPEN → RESOLVED                          (auto-resolve)
ACKNOWLEDGED → RESOLVED
```
Anything else throws `IllegalStateTransitionException` → HTTP 409. Implement as a `Map<IncidentState, Set<IncidentState>>` inside the enum, not scattered `if` blocks.

```java
@Entity
class Incident {
    UUID id;
    String correlationKey;      // UNIQUE where state != RESOLVED
    IncidentState state;
    Severity severity;          // max over member breaches
    String originService;       // inferred, nullable
    Set<String> affectedServices;
    Instant openedAt, acknowledgedAt, mitigatedAt, resolvedAt;
    String rcaDraft;            // null until RCA consumer fills it
    String rcaModel;
    Instant rcaGeneratedAt;
    List<IncidentEventLog> timeline;
}
```

**Idempotency — the interview-grade detail:**

```sql
CREATE UNIQUE INDEX idx_active_incident
  ON incident (correlation_key)
  WHERE state != 'RESOLVED';
```

Creation uses `INSERT … ON CONFLICT DO NOTHING` then re-reads. Two concurrent breaches for one key produce exactly one incident. There must be a test asserting this under 50 parallel threads.

**Two implementation traps here:**

1. **Hibernate does not generate `ON CONFLICT`.** Write it as a `@Modifying` native query on the
   repository and re-read via the normal derived finder. (Catching `DataIntegrityViolationException`
   and re-reading also works, but it burns a transaction on every duplicate — and duplicates are the
   common case during a storm, not the rare one.)

2. **A partial index requires its predicate at the conflict target.** Postgres only infers a partial
   unique index when the statement repeats the `WHERE` clause:

   ```sql
   INSERT INTO incident (...) VALUES (...)
   ON CONFLICT (correlation_key) WHERE state != 'RESOLVED' DO NOTHING;
   ```

   Omit the `WHERE` and Postgres looks for a *total* unique index on `correlation_key`, does not find
   one, and fails the statement. The 50-thread test catches this immediately — do not skip it.

---

## 6. Kafka design

### Topics

| Topic | Key | Partitions | Producer | Consumer |
|---|---|---|---|---|
| `slo.breach.v1` | `serviceName` | 3 | `SloEvaluator` | `BreachConsumer` |
| `incident.opened.v1` | `incidentId` | 3 | `IncidentService` | `RcaConsumer` |
| `incident.state-changed.v1` | `incidentId` | 3 | `IncidentService` | `AuditConsumer` (Phase 2, step 21a) |
| `*.DLT` | — | 1 | error handler | manual inspection |

`AuditConsumer` is the first thing on the cut list (§20). If you cut it, delete the topic too —
a declared topic with no consumer is a loose end a reviewer will notice.

**Keying by `serviceName`** puts all breaches for one service on one partition, preserving order — so "which service degraded first" is answerable within a partition. Say this out loud in interviews.

### Event schema

```java
public record SloBreachEvent(
    UUID eventId,           // DETERMINISTIC — see below
    UUID sloId,
    String serviceName,
    SloType sloType,
    Severity severity,
    double longBurnRate,
    double shortBurnRate,
    Instant detectedAt
) {}
```

`eventId` is a **deterministic name-based UUID** of `(sloId, severity, evaluationBucket)` where `evaluationBucket` is `detectedAt` truncated to the evaluation interval. A re-delivered event has the same ID, so consumer dedupe works.

Use `UUID.nameUUIDFromBytes(...)`. Note this produces a **v3 (MD5)** UUID — the JDK has no v5
(SHA-1) factory. Determinism is what matters here, so v3 is fine; just call it v3 in the README
and in interviews rather than claiming v5, because someone will check the version nibble.

**Never use `UUID.randomUUID()` for event IDs.**

### Consumer contract — every listener must

1. **Check the dedupe key** — Redis `GET processed:{eventId}`. Present → log DEBUG, ack, return.
2. **Do the work** inside a DB transaction.
3. **Set the dedupe key AFTER the transaction commits** — `SET processed:{eventId}` with 24h TTL.
4. **Manual ack** (`AckMode.MANUAL`), acked only after the commit.
5. **Classify failures** —
   - Non-retryable (`DeserializationException`, validation) → straight to DLT
   - Retryable (DB timeout, LLM 429) → `ExponentialBackOff(1s, 2.0)`, 3 attempts, then DLT
6. **Never swallow exceptions.** Every DLT publish increments a counter.

**Order matters — do not mark before committing.** The tempting version is `SETNX` first as a
cheap guard. It silently loses events:

```
SETNX succeeds  →  DB commit fails  →  exception  →  Kafka redelivers
                →  SETNX now finds the key  →  logs DEBUG, returns
                →  breach is gone. No incident, no DLT, no counter.
```

A dropped breach is the worst possible failure for this system — it is the one bug that makes the
product silently not do its job, and it produces no signal that it happened. Marking after commit
means the only remaining risk is a **duplicate delivery** in the window between commit and `SET`,
and that is exactly what the partial unique index (§5.5) exists to absorb.

This also sharpens the three-layer answer in §19.2. The layers are ordered, not redundant:

| Layer | Catches |
|---|---|
| Deterministic event ID | Makes redelivery *recognisable* at all |
| Redis dedupe (post-commit) | The cheap common case — skips work already done |
| Partial unique index | The commit↔mark race, and concurrent consumers on the same key |

```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template, MeterRegistry registry) {
    var recoverer = new DeadLetterPublishingRecoverer(template);
    var handler = new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(3));
    handler.addNotRetryableExceptions(DeserializationException.class, ValidationException.class);
    handler.setRetryListeners((rec, ex, attempt) ->
        registry.counter("sentinel.consumer.retry", "topic", rec.topic()).increment());
    return handler;
}
```

---

## 7. Correlation

Keep it simple and explainable. Do not reach for a graph database.

**On each `SloBreachEvent`:**

1. Push into Redis ZSET `breaches:recent`, score = epoch millis. TTL 10 min.
2. Read all breaches within `CORRELATION_WINDOW` (default 5m).
3. Compute the **component**:
   - Collect services with active breaches in the window
   - Build the subgraph of the static dependency graph **induced by those breached services only**
   - Find the **weakly connected component** containing this service
   - No breached neighbours → component is this service alone (solo incident)
4. Compute `originService` = earliest `detectedAt` among the component's breaches; ties broken by
   depth in the dependency graph (deeper = more likely the origin).
5. **`correlationKey = originService`.** Not a hash of the member set. See below.
6. `IncidentService.openOrAttach(correlationKey, event)`:
   - Idempotent insert (§5.5)
   - Append `IncidentEventLog` row
   - Recompute `severity` = max over members
   - Merge the component into `affectedServices` (union, never replace)
7. Newly created → publish `incident.opened.v1`

### Why the key is the origin and not the member set

The obvious key is `sha256(sorted(componentServices))`. **It breaks the headline demo.** The member
set grows as the cascade propagates, so every new breach produces a different key:

| Event | Breached set | `sha256(members)` | Result |
|---|---|---|---|
| ledger breaches | `{ledger}` | `a1b2…` | incident 1 opened |
| payment breaches | `{ledger, payment}` | `c3d4…` | incident 2 opened |
| cart breaches | `{ledger, payment, cart}` | `e5f6…` | incident 3 opened |
| checkout breaches | all four | `9a8b…` | incident 4 opened |

Four incidents — precisely the alert storm this project exists to collapse. And it fails *quietly*:
every individual step looks correct, the tests for a single breach pass, and you only see it when
you run the full cascade.

Keying on the origin is stable under growth. `ledger-service` breaches first and stays the earliest,
so all four events resolve to key `ledger-service`, attach to one incident, and widen its
`affectedServices` from 1 to 4. One incident, origin `ledger-service` — the §11 demo beat.

It also keeps the negative case honest: two breaches in *unconnected* components have different
origins, so they get different keys and two incidents (test scenario 4). Note that resolving the
component over the **induced subgraph** rather than the full static graph is what makes that test
meaningful — over the full graph the whole demo fleet is permanently one component, every breach
anywhere collapses into one incident, and correlation is doing no actual work.

**Known edge case, worth stating rather than fixing:** if a service breaches *later* but with an
earlier `detectedAt` than the current origin, the key would move. Resolve this by **freezing
`correlationKey` at incident creation** — recompute `originService` for display, but never re-key an
existing incident. Two components that later become connected therefore stay two incidents. That is
an acceptable, explainable boundary; merging live incidents is out of scope.

**Auto-resolution:** `@Scheduled` every 60s resolves incidents whose members have had no breach in `2 × CORRELATION_WINDOW`.

**State this limitation in the README:** time-window plus static-topology correlation, not causal inference. The dependency graph is configured, not discovered. Interviewers respect a stated boundary far more than an overclaim.

---

## 8. AI RCA

Triggered by `incident.opened.v1`, asynchronous, never blocks detection.

`TimelineBuilder` assembles compact structured context — not a raw dump:

```
INCIDENT:
  opened_at: 2026-08-06T02:14:31Z
  severity: CRITICAL
  affected: [checkout, cart, payment, ledger]
  inferred_origin: ledger-service

DEPENDENCY EDGES:
  checkout -> cart, payment
  cart -> payment
  payment -> ledger

BREACH TIMELINE:
  02:14:31  ledger-service    AVAILABILITY  burn=22.1
  02:14:46  payment-service   LATENCY       burn=17.3
  02:15:01  cart-service      LATENCY       burn=9.2
  02:15:01  checkout-service  AVAILABILITY  burn=15.5
```

**Prompt contract** (`prompts/rca-system.st`):

```
You are an SRE assistant drafting an initial incident hypothesis.

Rules:
- Use ONLY the data provided. Never invent log lines, error messages, deploys, or config changes.
- If the data is insufficient to identify a cause, say so explicitly.
- Output exactly these sections: SUMMARY, LIKELY ORIGIN, BLAST RADIUS, WHAT TO CHECK NEXT.
- Maximum 200 words.
- This is a hypothesis for a human to verify, not a conclusion.
```

**Resilience is mandatory:**

```java
@CircuitBreaker(name = "llm", fallbackMethod = "fallbackRca")
@Retry(name = "llm")
@TimeLimiter(name = "llm")
public String draft(IncidentContext ctx) { ... }

private String fallbackRca(IncidentContext ctx, Throwable t) {
    return TimelineBuilder.plainTextSummary(ctx);   // deterministic, no LLM
}
```

**The fallback is a feature.** The incident is still useful with a plain timeline. The AI is an enhancement layer and the system degrades gracefully without it — say exactly this in interviews.

**Provider:** Spring AI against an OpenAI-compatible endpoint, defaulting to Groq free tier.

```yaml
spring.ai.openai:
  base-url: ${LLM_BASE_URL:https://api.groq.com/openai}
  api-key: ${LLM_API_KEY:}
  chat.options: { model: ${LLM_MODEL:llama-3.3-70b-versatile}, temperature: 0.2 }
```

**`make demo` MUST work with no API key.** Empty key → circuit opens immediately → deterministic fallback. Non-negotiable: a reviewer will not sign up for Groq to try your project.

---

## 9. REST API

Base `/api/v1`. Static API key via `X-Api-Key` header (`OncePerRequestFilter`); Actuator and Swagger excluded.

Compare the key with `MessageDigest.isEqual(a, b)`, not `String.equals()`. `equals()` short-circuits
on the first differing byte and leaks the key a character at a time under timing analysis. It is a
one-line change, the scope here is deliberately a static key, and it is exactly the detail a
security-minded reviewer probes.

```
POST   /slos                       create                      201
GET    /slos                       list                        200
GET    /slos/{id}                                              200/404
PATCH  /slos/{id}                  enable/disable, retarget    200
DELETE /slos/{id}                                              204
GET    /slos/{id}/budget           burn + budget remaining     200

GET    /incidents?state=&severity=&since=   paged              200
GET    /incidents/{id}             detail + timeline           200/404
POST   /incidents/{id}/transition  {"to":"ACKNOWLEDGED"}       200/409
GET    /incidents/{id}/rca                                     200/202
POST   /incidents/{id}/rca:regenerate                          202

GET    /services                   fleet + dependency edges    200
```

Use `@RestControllerAdvice` returning **RFC 7807 `ProblemDetail`** — built into Spring Boot 3, do not hand-roll an error DTO.

---

## 10. Observability of Sentinel itself

```
sentinel.slo.evaluation.duration      Timer    tags: slo_type
sentinel.slo.evaluations.total        Counter  tags: result(ok|breach|insufficient)
sentinel.slo.cycle.duration           Timer    ← full cycle; THE scaling metric
sentinel.metrics.query.duration       Timer
sentinel.metrics.query.failures       Counter
sentinel.incidents.opened             Counter  tags: severity
sentinel.incidents.active             Gauge    tags: severity
sentinel.correlation.component.size   Summary  ← alerts collapsed per incident
sentinel.rca.duration                 Timer
sentinel.rca.fallbacks                Counter
sentinel.consumer.dlt                 Counter  tags: topic
```

`sentinel.correlation.component.size` is the money metric — literally "alerts suppressed per incident", and the source of your headline resume number.

`sentinel.slo.cycle.duration` is the scaling metric — when p99 approaches the 15s interval, you have found the ceiling.

**Dashboard 1 — Fleet SLO** (the product): per-service burn rate with threshold lines at 1/6/14.4, error budget gauges, active incidents table, incident annotations.

**Dashboard 2 — Sentinel internals** (the operations): cycle duration p50/p95/p99 with the interval as a threshold line, consumer lag per group, DLT counter, RCA latency and fallback rate, JVM memory and threads.

Both provisioned as JSON. They must exist on first boot with zero clicking.

---

## 11. Demo engineering

**The failure mode for infrastructure projects is that they look like nothing.** Empty list, flat graph, tab closed. Engineer against this deliberately.

### `make demo` — one command, full story

```makefile
demo: export SPRING_PROFILES_ACTIVE=demo
demo:
	docker compose up -d
	./scripts/wait-for-health.sh
	./scripts/seed-slos.sh
	docker compose up -d loadgen
	@echo "Grafana: http://localhost:3000  |  Swagger: http://localhost:8080/swagger-ui.html"
	@echo "Injecting cascading failure in 20s..."
	sleep 20
	./scripts/inject-cascade.sh
```

### Seed 30 days of history at startup

`DemoDataSeeder` inserts synthetic resolved incidents with varied severities and realistic timestamps on first boot (guarded by a row count check). An empty product looks broken; one with history looks like it has been running. ~50 lines, disproportionate impact.

### Baseline load generator — non-negotiable

A container hitting `checkout-service` at ~20rps continuously. **Without constant traffic, `rate()` returns nothing and the entire system silently does nothing.** This is the single most common way this kind of project fails to work on first run.

### The two-minute demo script — rehearse it

| Time | Action | What they see |
|---|---|---|
| 0:00 | `make demo` | Stack up, Grafana green, four services healthy |
| 0:20 | Chaos hits `ledger-service` | Burn rate starts climbing |
| 0:45 | Burn crosses 14.4 | `ledger-service` CRITICAL |
| 1:00 | Cascade propagates | Four services breaching — *four alerts in a naive system* |
| 1:10 | `GET /incidents` | **ONE** incident, `originService: ledger-service` |
| 1:25 | `GET /incidents/{id}/rca` | Narrative naming ledger as origin |
| 1:40 | `docker kill sentinel` → restart | Incident survives, evaluation resumes, no duplicates |
| 1:55 | Internals dashboard | Consumer lag, cycle p99, alerts-collapsed |

**These timings assume the `demo` profile's compressed windows (§16).** On the production 1h/5m
windows the first breach lands around t+3min and the whole script falls apart. Verify the profile is
active before recording the GIF.

**The 1:40 kill-and-restart is what separates this from a CRUD app.** Do not skip it.

### Three artifacts above the fold in the README

Demo GIF (30s: cascade → one incident → RCA), architecture diagram, `make demo`. **Assume nobody runs your code.** The GIF carries it.

---

## 12. Load testing — where the resume numbers come from

### The synthetic exporter is the trick

You do not need 500 real services to prove 500 services. The evaluator queries Prometheus for time series and does not care where they came from.

`synthetic-exporter` is one process exposing `SYNTHETIC_SERVICES=N` fake service series with configurable error rate and latency distribution, scraped by Prometheus. **Load test knob is a single env var.** No 500 containers, no 32GB of RAM.

Keep the 4 real fleet services for the demo (they cascade properly and tell the story). The exporter exists purely for stress testing.

```java
// SeriesGenerator — expose N services' worth of counter/histogram series
// Env: SYNTHETIC_SERVICES, SYNTHETIC_ERROR_RATE, SYNTHETIC_BREACH_FRACTION
// Must also emit a synthetic dependency graph so correlation is exercised
```

### The five measurements

Run each on a fixed machine, record the spec, publish raw output in `docs/LOAD_TEST_RESULTS.md`.

**1. Evaluation throughput — finds the ceiling**

Ramp `SYNTHETIC_SERVICES` = 100 / 250 / 500 / 1000 / 2000. Measure `sentinel.slo.cycle.duration` p50/p95/p99. **The ceiling is where p99 approaches the 15s interval.** That number is the headline.

**2. Breach storm — end-to-end latency under load**

Flip 30% of synthetic services into breach simultaneously. Measure detection → incident committed, plus peak consumer lag and recovery time.

**3. Alert collapse ratio — the product's value as a number**

Cascading failure across the synthetic dependency graph. `raw breaches ÷ incidents created`. This is the money number.

**4. Recovery time — durability with a number**

`docker kill` mid-cycle. Measure time to resume normal evaluation. Assert zero duplicate incidents.

**5. Duplicate replay — correctness under duplication**

Replay 10,000 duplicate events. Assert zero duplicate incidents. Not a performance number — it is the evidence behind your idempotency claim.

### `docs/LOAD_TEST_RESULTS.md` template

```markdown
## Environment
Machine: <CPU, RAM, OS>   Docker: <version>   Java: 21
Single sentinel-platform instance (shardCount=1)

## Evaluation throughput
| Services | SLOs | Cycle p50 | Cycle p95 | Cycle p99 | Interval drift |
|---|---|---|---|---|---|
| 100  | 200  | | | | 0s |
| 500  | 1000 | | | | 0s |
| 1000 | 2000 | | | | |
| 2000 | 4000 | | | | |

Ceiling: <N> services on one instance before p99 exceeds the 15s interval.
First bottleneck observed: <what>

**Drift, not "cycles missed."** `@Scheduled(fixedDelay=15s)` measures the gap *after* the previous
cycle returns, so cycles can never overlap and none is ever skipped — the schedule just slips.
Measure `(actual elapsed wall time) − (cycles × 15s)` over the run. Zero drift means the evaluator is
keeping up; growing drift means it is not, and the rate of growth tells you by how much.

## Alert collapse
Raw breaches: <A>   Incidents: <B>   Ratio: <A/B>:1

## Recovery
Kill-to-steady-state: <Y>s   Duplicate incidents: 0

## Duplicate replay
Events replayed: 10,000   Incidents created: <expected>   Duplicates: 0
```

---

## 13. Scaling analysis — `docs/SCALING.md`

Write this document. It is what you read from when asked "how does this scale in production", and having it written means you answer with a number instead of a hand-wave.

### Back-of-envelope

At 100 services × 2 SLOs, with recording rules, the evaluator issues **~6 Prometheus queries per cycle** (one per window, all services in one vector) — not 400. Postgres sees a handful of incident writes per hour. Kafka is idle. **Single instance is genuinely fine well past 500 services.**

### What breaks first, in order

1. **Single-instance `@Scheduled` evaluator** — naive replicas would each evaluate every SLO, producing duplicate events and triple-counted burn rate. This is why `ShardAssignment` exists from day one.
2. **Redis correlation window** — one ZSET read per breach event becomes a hot key during a large incident, exactly when you can least afford it.
3. **Prometheus itself** — single instance is a retention and query bottleneck long before your evaluator is.
4. **Connected-component walk** — O(V+E) per event; fine at hundreds, not at 10,000 with a dense graph.

### The fixes, in the order you would do them

| Bottleneck | Fix | Cost |
|---|---|---|
| Evaluator throughput | Run N replicas with distinct `shardIndex`/`shardCount` | Config change — already built |
| Evaluator HA (not throughput) | `ShedLock` — one active, others warm standby | Small |
| Redis hot key | `CorrelationStore` → Kafka Streams windowed state store, partitioned by correlation key | Moderate — the seam exists |
| Component walk | Cache precomputed component map, invalidate on topology change | Small |
| Prometheus | Thanos or Mimir behind `MetricsSource` | Moderate — the seam exists |

### The framing

Do not answer "here's how it scales." Answer:

> "It is designed for the scale I built and measured it at — <N> services on a single instance. The first thing that breaks is the single-instance evaluator, at roughly <N> services where cycle p99 approaches the 15s interval. The fix is sharding by service hash, which the code already supports via config. The next thing is the Redis correlation window, which would move to a Kafka Streams state store."

Naming your own ceiling with a measured number reads as senior. Claiming unlimited scale reads as someone who has not measured.

---

## 14. Test plan

### 14.1 Unit — `slo.math` (JUnit 5, no Spring context)

`@ParameterizedTest` with `@CsvSource`:

| errorRate | objective | burn | expected |
|---|---|---|---|
| 0.0 | 0.999 | 0.0 | Ok |
| 0.0005 | 0.999 | 0.5 | Ok |
| 0.001 | 0.999 | 1.0 | Breach(MEDIUM) |
| 0.006 | 0.999 | 6.0 | Breach(HIGH) |
| 0.0144 | 0.999 | 14.4 | Breach(CRITICAL) |

**A threshold fires at exactly its value** (`burn >= threshold`), so burn 1.0 is a MEDIUM breach, not
`Ok`. The two are not independently choosable: with `>` instead, burn 14.4 would *not* fire CRITICAL
and the headline demo stops working. Pick `>=`, and use 0.0005 for the "clearly healthy" row so no
row sits on a boundary by accident.

Compare with a small epsilon (`burn >= threshold - 1e-9`). `0.0144 / 0.001` does not land exactly on
14.4 in IEEE 754, so an exact comparison makes the CRITICAL boundary depend on representation error.
Burn rates are order 1 to 100; 1e-9 is far below anything meaningful.

Plus explicit tests: zero events, too-few events, `objective=1.0` rejection, NaN/±Inf/out-of-range
ratios, partial window (coverage), highest-severity-wins, and both one-sided multi-window cases
(long hot + short cool must NOT fire, and the reverse).

**PIT mutation testing on this package only:**
```xml
<targetClasses><param>com.sentinel.slo.math.*</param></targetClasses>
<mutationThreshold>85</mutationThreshold>
```

### 14.2 Unit — state machine

All 16 `(from,to)` pairs, table-driven, one test method. Legal succeed, illegal throw.

### 14.3 Integration — Testcontainers

One `@SpringBootTest` base class with `@ServiceConnection` containers for Postgres, Redpanda, Redis. **Reuse containers across classes** (static fields) or the suite takes ten minutes.

Required scenarios:

1. **Happy path** — publish breach → incident appears with correct severity, origin, affected set. Awaitility, never `sleep`.
2. **Idempotency under concurrency** — 50 threads publish the same `eventId` → exactly one incident, one timeline entry.
3. **Correlation** — 4 breaches across connected services in-window → ONE incident, 4 affected, correct origin.
4. **No false correlation** — 2 breaches, unconnected services → TWO incidents.
5. **DLT routing** — malformed payload → lands on DLT, consumer keeps working on the next valid message.
6. **Retry then DLT** — WireMock LLM returns 500 three times → 3 retries, then DLT, incident still has fallback RCA.
7. **Auto-resolve** — open incident, stop breaches, advance an injected `Clock` → RESOLVED. Do not sleep.
8. **Consumer restart** — stop/restart listener container mid-stream → no lost, no duplicated incidents.
9. **Shard filtering** — `shardCount=2`, assert each shard evaluates a disjoint service set and the union is complete.

### 14.4 WireMock — external boundaries

Prometheus: canned range-query responses, plus 500s and timeouts for Resilience4j retry. LLM: canned completion, plus 429 and 500 for circuit breaker and fallback.

### 14.5 CI (`.github/workflows/ci.yml`)

```
build → unit tests → JaCoCo gate (line ≥75%, branch ≥65%)
      → integration tests (Testcontainers)
      → PIT on slo.math (≥85%)
      → spotless / checkstyle
```

Badges in the README.

---

## 15. Build phases

Do not start a phase until the previous one runs and its tests pass.

### Phase 1 — Foundation (Weekend 1)

**Goal:** metrics flow, SLOs evaluate, breaches detected. No Kafka yet.

1. Parent POM, modules, `.gitignore`, spotless, `Makefile` skeleton
2. `demo-fleet`: `WorkController`, `ChaosController` (`/chaos/latency`, `/chaos/errors`, `/chaos/hang`, `/chaos/reset`), Micrometer with explicit buckets, `service` tag from env
3. `DependencyCaller` — synchronous downstream calls so failures cascade
4. `docker-compose.yml` v1 — 4 fleet instances, Prometheus, Grafana
5. **Load generator container** — ~20rps against checkout. Without this nothing works.
6. Prometheus recording rules (§5.3)
7. `sentinel-platform` skeleton, Postgres, Flyway `V1__slo.sql`, SLO CRUD + validation
7a. **`Clock` bean** — `@Bean Clock clock() { return Clock.systemUTC(); }`, injected anywhere a
    timestamp is taken. Do this now, in Phase 1, even though nothing needs to fake time yet.
    The auto-resolve test (scenario 7) advances an injected `Clock` rather than sleeping, and
    retrofitting `Clock` through `Incident`, `SloEvaluator`, and the correlation window in Phase 2
    touches every timestamp in the codebase. Ten minutes now, an afternoon later.
8. `MetricsSource` interface + `PrometheusMetricsSource` (single vector query per window) with Resilience4j retry
9. **`slo.math` — write the full unit suite FIRST, before wiring**
10. `ShardAssignment` (defaults 0/1)
11. `SloEvaluator` — `@Scheduled(fixedDelay=15s)`, virtual threads, shard-filtered, logs breaches
12. Grafana fleet dashboard provisioned

**Done when:** with the `demo` profile active (§16), `curl -X POST localhost:8081/chaos/errors?rate=0.3`
→ within 60s a CRITICAL breach appears in logs and the Grafana burn rate panel climbs.

Do not use the production windows for this check. A 1h long window needs ~3 minutes at `rate=0.3`
to reach burn 14.4, and on a freshly started stack it will appear to trip much faster because
`rate()` only has seconds of history to work with. Both behaviours are correct; neither is a stable
acceptance test.

### Phase 2 — Event-driven core (Weekend 2)

**Goal:** breaches become correlated incidents, durably and idempotently.

13. Redpanda + Redis in compose. `KafkaConfig`: topic beans, JSON ser/de, manual ack, `DefaultErrorHandler` + DLT
14. `EventPublisher` interface + Kafka impl + in-memory impl for tests
15. `SloBreachEvent` with deterministic `eventId`; evaluator publishes instead of logging
16. Flyway `V2__incident.sql` — incident, event log, service_dependency, partial unique index
17. Seed `service_dependency` from `application.yml` on startup
18. `CorrelationStore` interface + `RedisCorrelationStore`; `DependencyGraph` component walk
19. `IncidentService` — idempotent `openOrAttach`, state machine, origin inference
20. `BreachConsumer` — dedupe → correlate → open/attach → ack
21. `IncidentController` + auto-resolve scheduler
21a. `AuditConsumer` on `incident.state-changed.v1`. First on the cut list (§20) — if you cut it,
     delete the topic declaration too.
22. **Full Testcontainers suite (scenarios 1–5, 7, 8, 9)**
23. Custom Micrometer metrics + internals dashboard

**Done when:** breaking `ledger-service` produces exactly ONE incident with four affected services and `originService: ledger-service`, and the 50-thread idempotency test passes.

### Phase 3 — Intelligence, demo, proof (Weekend 3)

**Goal:** RCA, demo polish, measured numbers.

24. Spring AI config, prompt template, `TimelineBuilder`
25. `RcaDrafter` — circuit breaker, retry, time limiter, deterministic fallback
26. `RcaConsumer`; Flyway `V3__rca.sql`
27. WireMock LLM failure tests (scenario 6)
28. springdoc-openapi + API key filter
29. `DemoDataSeeder` — 30 days of history
30. `make demo` + `wait-for-health.sh`, `seed-slos.sh`, `inject-cascade.sh`
31. **`synthetic-exporter` module**
32. **k6 scripts + the five measurements → `docs/LOAD_TEST_RESULTS.md`**
33. **Write `docs/SCALING.md`**
34. GitHub Actions CI with JaCoCo + PIT
35. README with GIF, architecture diagram, "why not just Prometheus + Alertmanager"

**Done when:** a stranger clones, runs `make demo`, and reaches the RCA in under two minutes with no API key — and `LOAD_TEST_RESULTS.md` has real numbers.

### Phase 4 — Kubernetes (1–2 days, optional but recommended)

**Goal:** a legitimate, defensible Kubernetes claim. Compose stays the primary path.

36. `kind-cluster.yaml` + `make kind-up` (nothing → running cluster)
37. Helm chart: Deployments, Services, ConfigMaps, Secrets for all components
38. **Probes wired to Actuator:**
    - `readinessProbe` → `/actuator/health/readiness`
    - `livenessProbe` → `/actuator/health/liveness`
    - `startupProbe` with generous `failureThreshold` (Spring Boot + Flyway is slow to start)
39. **Custom readiness indicator** — NOT_READY when the Kafka consumer has no partition assignment, or `MetricsSource.isHealthy()` is false. This is the strong answer to "how does K8s know your service is ready?"
40. **Graceful shutdown** — `terminationGracePeriodSeconds: 45`, `spring.lifecycle.timeout-per-shutdown-phase: 30s`, `preStop` sleep so endpoints deregister before SIGTERM
41. **HPA on Kafka consumer lag** (KEDA or custom metric), not CPU. **Evaluator stays at replica 1** unless sharded — document why in `k8s/README.md`.
42. **Prometheus Kubernetes service discovery** — pod annotations instead of static targets
43. `k8s/README.md` — what this demonstrates, and honestly what it does not (no managed cluster ops, no mesh, no operators)

**Done when:** `make kind-up && helm install sentinel ./k8s/helm/sentinel` gives a working stack, and killing a pod mid-message shows graceful drain with no lost incidents.

**The interview payoff:** *"What happens to an in-flight Kafka message when a pod is evicted?"* → manual ack means the offset is not committed, the group rebalances, another pod reprocesses, and the idempotency layer makes reprocessing safe. That answer is a direct payoff from Phase 2.

---

## 16. Configuration reference

```yaml
sentinel:
  evaluation:
    interval: 15s
    parallelism: 8
    shard-index: ${SHARD_INDEX:0}
    shard-count: ${SHARD_COUNT:1}
  correlation:
    window: 5m
    auto-resolve-after: 10m
  slo:
    windows:
      critical: { long: 1h, short: 5m,  burn-threshold: 14.4 }
      high:     { long: 6h, short: 30m, burn-threshold: 6.0 }
      medium:   { long: 3d, short: 6h,  burn-threshold: 1.0 }
  dependencies:
    checkout-service: [cart-service, payment-service]
    cart-service: [payment-service]
    payment-service: [ledger-service]
    ledger-service: []
  api-key: ${SENTINEL_API_KEY:local-dev-key}
  demo-seed: ${DEMO_SEED:true}

spring:
  jpa.hibernate.ddl-auto: validate
  lifecycle.timeout-per-shutdown-phase: 30s

resilience4j:
  circuitbreaker.instances.llm:
    failure-rate-threshold: 50
    wait-duration-in-open-state: 30s
    sliding-window-size: 10
  retry.instances:
    llm:        { max-attempts: 3, wait-duration: 2s, exponential-backoff-multiplier: 2 }
    prometheus: { max-attempts: 3, wait-duration: 500ms }
```

### The `demo` profile — compressed windows

`application-demo.yml`, activated by `make demo`:

```yaml
sentinel:
  slo:
    windows:
      critical: { long: 5m,  short: 1m,  burn-threshold: 14.4 }
      high:     { long: 10m, short: 2m,  burn-threshold: 6.0 }
      medium:   { long: 30m, short: 5m,  burn-threshold: 1.0 }
  correlation:
    window: 2m
    auto-resolve-after: 4m
```

**This is required for the demo to work, not a convenience.** Run the arithmetic on the production
windows: 20rps baseline, 30% injected errors, `objective = 0.999` so `errorBudget = 0.001`.

After 60s of chaos, the trailing **1h** window holds 1200 bad requests out of 72,000 → error rate
0.5% → burn rate **5.0**. That is `HIGH`, not `CRITICAL`. You do not cross 14.4 until roughly
**t+3min**. The Phase 1 acceptance check and the 0:20→0:40 beats in the §11 script are simply not
reachable with a 1h long window.

**There is a trap hiding behind this.** On a cold `make demo`, Prometheus holds only ~20s of
history, and `rate()` computes over the samples that exist rather than the nominal range — so the
burn rate spikes almost immediately and the demo appears to work. Leave the stack up for an hour and
the same chaos injection takes minutes to trip. **The demo works cold and degrades as it runs.**
That is the failure mode where it works all week and dies on the shared screen.

Compressed windows make the timing deterministic regardless of uptime. Keep the production values as
the default, activate `demo` only for `make demo`, and say so in the README — a reviewer who spots
the profile should find it already explained rather than looking like a fudge.

---

## 17. README requirements

In this order:

1. **One-sentence pitch** (§0)
2. **Demo GIF**, above the fold
3. **`make demo`** — one command, works with no API key
4. **Architecture diagram**
5. **"Why not just Prometheus + Alertmanager?"** — Recording rules compute burn rate; Alertmanager groups and inhibits. What they do not give you: a stateful incident entity with a lifecycle, dependency-aware cross-service correlation, error budget accounting that survives restarts, or queryable incident history for postmortems. That is the layer PagerDuty and incident.io sell; this is a minimal open version.
6. **Measured numbers** — headline results from `LOAD_TEST_RESULTS.md` with hardware spec
7. **Design decisions** — why Kafka over direct calls, why the partial unique index, why deterministic event IDs, why the LLM has a deterministic fallback, why burn rate is computed in Prometheus
8. **The five seams** — table from §3
9. **Known limitations** — time-window + static-topology correlation not causal inference; configured not discovered dependency graph; incidents never merge once opened (§7); single tenant; single-instance evaluator below the measured ceiling. State plainly that `make demo` runs compressed SLO windows (§16) so a cascade is visible in two minutes, and that production defaults are the standard 1h/6h/3d — declaring it is stronger than having someone find it.
10. **Testing** — call out failure-path tests and mutation coverage explicitly
11. **Kubernetes** — link to `k8s/README.md`, noting Compose is the primary path

---

## 18. Resume bullets — fill in AFTER measuring

Do not write these until `LOAD_TEST_RESULTS.md` has real numbers.

> Built an event-driven SLO and incident intelligence platform (Java 21, Spring Boot 3, Kafka, PostgreSQL, Redis) evaluating multi-window error-budget burn rate across `N` services at 15s intervals with p99 cycle latency of `X`ms, correlating dependency-related breaches into single incidents at an `A:B` alert-collapse ratio under cascading-failure load tests.

> Designed idempotent Kafka consumers with deterministic event IDs, dead-letter routing, and per-service partitioning; verified broker restart, consumer rebalance, duplicate-delivery, and process-kill recovery (`Y`s to steady state, zero duplicates across 10,000 replayed events) using Testcontainers integration tests at `Z`% branch coverage and 85% mutation coverage on the SLO evaluation core.

> Instrumented with Micrometer, Prometheus recording rules, and provisioned Grafana dashboards; deployed to Kubernetes via Helm with Actuator-backed readiness probes tied to Kafka partition assignment and graceful shutdown ensuring in-flight messages are safely reprocessed on pod eviction.

---

## 19. Interview questions this invites

Have an answer ready for each.

1. *"Why Kafka, not direct calls?"* → Different latency and failure profiles; a 10s LLM call must not block a 15s cycle; replayability; back-pressure isolation.
2. *"What if the same breach is delivered twice?"* → Three layers: deterministic UUIDv5 event ID, Redis SETNX dedupe, partial unique index as last defence. Walk through all three.
3. *"How do you know which service caused it?"* → Earliest breach in the connected component, ties by graph depth. Then state the limit: heuristic, not causal.
4. *"Prometheus already does this."* → §17 item 5. Know exactly where the boundary is.
5. *"What if Prometheus is down?"* → Resilience4j retry, then `InsufficientData` — never a false breach. Sentinel failing must not manufacture incidents.
6. *"How does this scale?"* → §13. Lead with the measured ceiling, name the first bottleneck, name the fix.
7. *"Why not Kafka Streams for correlation?"* → Did not need it at measured scale; `CorrelationStore` is the seam; here is exactly when I would switch.
8. *"How does K8s know your service is ready?"* → Custom readiness indicator tied to Kafka partition assignment, not just a default health endpoint.
9. *"In-flight message during pod eviction?"* → Manual ack, uncommitted offset, rebalance, reprocess, idempotency makes it safe.
10. *"Why did you build this?"* → Years of production support at TCS, drowning in alerts, doing correlation by hand. This is the layer that should have existed.

---

## 20. Guardrails for Claude Code

**Never:**
- Add dependencies not in §2 without a POM justification comment
- Build a React frontend — Grafana and Swagger are the UI
- Use `ddl-auto: update` — Flyway only, `validate` mode
- Use `Thread.sleep()` in tests — Awaitility or an injected `Clock`
- Use `UUID.randomUUID()` for event IDs — deterministic only
- Call `Instant.now()` directly — inject the `Clock` bean
- Set the Redis dedupe key before the DB transaction commits — it silently drops events (§6)
- Key an incident on the correlated member set — it grows mid-cascade and splits the incident (§7)
- Re-key an existing incident; `correlationKey` is frozen at creation
- Divide two PromQL vectors with different label sets without `on(...) group_left` — returns empty, not an error
- Require an LLM API key for `make demo` to work
- Write four separate fleet services — one app, four env-configured instances
- Query Prometheus per-service — recording rules, one vector query per window
- Let PromQL leak outside `slo.metrics`
- Put Spring, I/O, or `Instant.now()` inside `slo.math`
- Add a sixth abstraction seam

**Always:**
- Write `slo.math` tests before wiring the evaluator — the only mandatory-TDD package
- Keep the baseline load generator running — without traffic, `rate()` is empty and nothing works
- Commit phase-scoped with conventional messages (`feat(slo):`, `test(incident):`) — the history is read
- Keep `make demo` on the Compose path working after every phase

**If a phase runs long, cut in this order:** auto-resolve scheduler → `AuditConsumer` → Phase 4 Kubernetes → RCA regeneration endpoint. Never cut: the load generator, the idempotency tests, `slo.math` coverage, or the demo script.
