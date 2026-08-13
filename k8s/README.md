# Sentinel on Kubernetes

**Docker Compose is the primary path.** `make demo` at the repository root is the two-minute demo,
and nothing here is needed to run it. This directory is a second, real deployment target — the same
images, the same behaviour, expressed as a Helm chart on a local `kind` cluster.

It exists because a few of Sentinel's design decisions only become visible under an orchestrator.
Manual Kafka acks matter when a pod is evicted. A readiness probe matters when something is deciding
whether to route to you. Both are claims this project makes; here they are testable rather than
asserted.

---

## Quickstart

Needs `docker`, `kind`, `kubectl` and `helm`.

```bash
make kind-demo               # or: ./scripts/kind-demo.sh   (Windows / no make)
```

Three steps behind one target: create the cluster, build and side-load the images, `helm upgrade
--install`. Then open **<http://localhost:3000>** and press **Start demo** — the same console, at the
same URL, as the Compose path, because `k8s/kind-cluster.yaml` maps the node ports onto the host.

| | |
|---|---|
| Demo console | <http://localhost:3000> |
| Swagger UI | <http://localhost:3000/swagger-ui.html> |
| Grafana | <http://localhost:3001> (anonymous, no login) |
| Prometheus | <http://localhost:9090> |

```bash
make kind-status   # pods, and what each probe currently says
make kind-drain    # delete the sentinel pod mid-incident; nothing lost, nothing duplicated
make kind-down     # delete the cluster
make helm-lint     # lint and render the chart; needs no cluster at all
```

Sizing: eight fleet JVMs, Sentinel, Postgres, Redpanda, Redis, Prometheus and Grafana. Give Docker
Desktop at least **8GB**. `--set demoFleet.enabled=false` drops the fleet if you only want the
platform.

---

## What this actually demonstrates

Four things, each of which is a question an interviewer asks and this answers with a file rather
than an opinion.

### 1. Readiness that means something

The strong version of "how does Kubernetes know your service is ready?" is not "I exposed
`/actuator/health`". Two custom indicators back the readiness group
([`KafkaReadinessIndicator`](../sentinel-platform/src/main/java/com/sentinel/observability/KafkaReadinessIndicator.java),
[`MetricsSourceReadinessIndicator`](../sentinel-platform/src/main/java/com/sentinel/observability/MetricsSourceReadinessIndicator.java)):

- **No Kafka partition assignment → unready.** A replica that has joined the group but holds no
  partitions will serve the incident API perfectly and consume nothing. During a rolling update
  that is exactly when breaches are most likely, and a default probe cannot see it — the process is
  up, the context refreshed, the port open.
- **Metrics source unreachable → unready.** An evaluator that cannot reach Prometheus evaluates
  nothing. It does not evaluate *wrongly* — an unreachable source yields `InsufficientData`, never a
  breach, so Sentinel failing can never manufacture an incident — but it is not doing the job
  either.

The three probes ask three different questions, and conflating them is the classic way an outage in
a dependency becomes a `CrashLoopBackOff`:

| Probe | Endpoint | Question |
|---|---|---|
| `startupProbe` | `/actuator/health/liveness` | has it finished booting? 60 × 5s, because Flyway plus context refresh is slow on a cold cluster |
| `livenessProbe` | `/actuator/health/liveness` | is the process wedged? `livenessState` only — deliberately depends on nothing |
| `readinessProbe` | `/actuator/health/readiness` | should traffic come here? the two indicators above |

Two details worth pointing at:

- The **startup probe deliberately does not use readiness.** Readiness depends on Prometheus; a
  startup probe that waits on a dependency turns a slow dependency into a crash loop.
- The indicators report **`OUT_OF_SERVICE`, not `DOWN`**, and `application.yml` maps
  `OUT_OF_SERVICE` to 200 at the root health endpoint while the readiness group keeps 503. So
  Kubernetes pulls the pod out of the Service, and nothing restarts a container that is still
  perfectly able to serve incident history. The root endpoint is what the Compose healthcheck
  watches, and a Prometheus outage must not fail that.

Asserted in
[`ReadinessProbeIT`](../sentinel-platform/src/test/java/com/sentinel/observability/ReadinessProbeIT.java),
which runs a real Redpanda and no Prometheus — exactly the split that matters.

### 2. Graceful shutdown, and what happens to an in-flight message

The payoff from Phase 2. Ask *"what happens to an in-flight Kafka message when a pod is evicted?"*
and the answer is a chain, not a hope:

1. `preStop` sleeps 5s. Endpoint removal and `SIGTERM` are dispatched **concurrently**, not in
   order, so without this the pod can stop accepting connections before kube-proxy has finished
   removing it from the Service.
2. `SIGTERM` → Spring's graceful shutdown. `spring.lifecycle.timeout-per-shutdown-phase: 30s` gives
   in-flight work time to finish, and the listener containers stop before the context closes.
3. Anything not finished **never had its offset committed** — the consumers use `AckMode.MANUAL` and
   ack only after the database transaction commits.
4. The group rebalances, the replacement pod reprocesses, and the idempotency layers make
   reprocessing a no-op: a deterministic event ID makes the redelivery recognisable, Redis dedupe
   skips work already done, and the partial unique index on `correlation_key` absorbs the race
   between commit and mark.

`terminationGracePeriodSeconds: 45` is the sum with headroom — 5 preStop + 30 shutdown + ~10 spare.
Overrun it and the kubelet sends `SIGKILL`, which drops whatever the consumer was holding.

`make kind-drain` runs it: break the order path, delete the pod while breaches are in flight, and
assert the incident count did not go backwards and no correlation key has two active incidents.

### 3. Prometheus service discovery instead of a static target list

Under Compose the eight fleet targets are written out by hand and a ninth service means editing
`prometheus.yml`. Here a pod is scraped because it carries `prometheus.io/scrape=true`, so scaling
the fleet, rolling it, or turning on sharding changes the scrape set with no config edit and no
restart. Backed by a ServiceAccount and a read-only ClusterRole over pods, services, endpoints and
nodes.

One deliberate omission in the relabeling: **the `service` label is not derived from any Kubernetes
object.** It is emitted by the applications themselves, from `SERVICE_NAME`. That label is the
domain identity — the thing the recording rules aggregate by, the key in the dependency graph, the
correlation key of an incident. Rebinding it to a pod or Service name would silently break
correlation the moment the two diverged, which is why every fleet Deployment is named
`sentinel-cart-service` while the app inside still calls itself `cart-service`.

### 4. Scaling, honestly

`replicas: 1`, and the comment in [the template](helm/sentinel/templates/sentinel.yaml) says why at
length. The evaluator is an `@Scheduled` loop over every enabled SLO; two replicas at `shardCount=1`
both own every SLO, so each cycle runs twice against the same recording-rule series and both publish
a breach. Deterministic event IDs and the partial unique index make that *survivable* — but
surviving duplicated work is not scaling, and the wasted Prometheus queries are real. The update
strategy is `Recreate` for the same reason: a rolling update would briefly run two replicas.

Scaling out is a values change, not a code change, because `ShardAssignment` was built in Phase 1:

```bash
helm upgrade sentinel ./k8s/helm/sentinel -n sentinel \
  --set sentinel.sharding.enabled=true --set sentinel.sharding.shardCount=3
```

That renders a **StatefulSet** rather than a Deployment — purely for stable ordinals, since there is
no per-pod state. Pod `sentinel-2` reads `2` out of its own hostname into `SHARD_INDEX` and owns the
SLOs whose service name hashes to shard 2. A Deployment cannot do this: its pods are interchangeable
and their names are random.

```bash
kubectl -n sentinel logs -l app.kubernetes.io/component=sentinel --tail=-1 | grep "starting shard"
```

---

## KEDA, and why it ships disabled

`keda.enabled=true` renders a `ScaledObject` that scales on **Kafka consumer lag, not CPU**. CPU is
the wrong signal here and it is worth being precise about why: the consumers spend their time
waiting — on Postgres, on Redis, on the broker — so during the exact event that matters, a breach
storm producing thousands of messages, CPU barely moves. Lag is the backlog itself rather than a
proxy for it, and `lagThreshold` is a statement about how far behind detection may fall, which is
something an SRE can actually reason about.

**It is off by default, and the reason is the interesting part.** Sentinel is one deployable holding
both roles — the scheduled evaluator and the Kafka consumers. KEDA scaling this workload therefore
scales the evaluator too, straight back into the duplicate-evaluation problem above. Replicas whose
cycles land in the same evaluation bucket produce identical event IDs and get deduped; replicas that
straddle a bucket boundary do not.

The honest fix is to split the two roles into separate Deployments: one evaluator (or a sharded
StatefulSet) and a lag-scaled consumer pool. That needs a role flag in the application, not a chart
change. It is future work, written down here rather than papered over.

Requires KEDA in the cluster:

```bash
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace
```

---

## What this is not

Stating the boundary is worth more than implying there isn't one.

- **No managed cluster operations.** `kind`, on a laptop. No node pools, no upgrades, no cluster
  autoscaling, no multi-zone anything, no cloud provider.
- **No service mesh, no operators, no CRDs of our own.** The only third-party CRD is KEDA's
  `ScaledObject`, and it is opt-in.
- **The backing services are demo-grade.** Single-replica Postgres, single-node Redpanda, one Redis
  with persistence deliberately off. No replication, no backups, no connection pooler, no PodDisruptionBudgets.
  A real deployment points `DB_URL`, `KAFKA_BOOTSTRAP` and `REDIS_HOST` at managed instances and
  deletes those three templates.
- **Prometheus is an `emptyDir`.** Losing it costs the Grafana graphs their backfill. The retention
  that matters — incident history — is in Postgres, which has a PVC.
- **No NetworkPolicies, no PodSecurityContext hardening, no non-root enforcement beyond what the
  images already do.** Single-tenant demo cluster; there is nothing to isolate from.
- **No Ingress or TLS.** NodePorts mapped to host ports by `kind-cluster.yaml`, so the URLs match
  the Compose path.
- **Secrets are a `Secret`, not a secret manager.** The point of the template is that credentials
  arrive by `secretKeyRef` rather than sitting in a Deployment spec where `kubectl get deploy -o
  yaml` prints them. The values themselves are `local-dev-key` and `sentinel`.

---

## Layout

```
k8s/
├── README.md
├── kind-cluster.yaml            1 node, node ports mapped to 3000 / 3001 / 9090
└── helm/sentinel/
    ├── Chart.yaml
    ├── values.yaml              every knob, each with the reason it exists
    ├── files/                   copies of infra/ and loadtest/ assets — see below
    └── templates/
        ├── _helpers.tpl         names, labels, cross-component hostnames
        ├── _sentinel-pod.tpl    the pod spec, shared by Deployment and StatefulSet
        ├── sentinel.yaml        Deployment or sharded StatefulSet, plus Services
        ├── sentinel-config.yaml ConfigMap (in-cluster fleet addresses) and Secret
        ├── postgres.yaml        StatefulSet + PVC
        ├── redpanda.yaml        StatefulSet + PVC
        ├── redis.yaml           Deployment, no persistence, on purpose
        ├── prometheus-config.yaml   scrape config with pod SD, and the recording rules
        ├── prometheus.yaml      ServiceAccount, RBAC, Deployment, Service
        ├── grafana-config.yaml  datasource and both dashboards
        ├── grafana.yaml         Deployment, Service
        ├── demo-fleet.yaml      one image, eight Deployments, generated from values
        ├── loadgen-config.yaml  the k6 baseline script
        ├── loadgen.yaml         the traffic without which nothing works
        ├── keda-scaledobject.yaml
        └── NOTES.txt
```

### The duplicated files

Helm cannot read anything above the chart root, so the recording rules, both dashboards and the
baseline k6 script exist twice — once in `infra/` and `loadtest/`, once in `helm/sentinel/files/`.
Duplication nothing enforces is duplication that rots, and two copies of burn-rate arithmetic would
eventually disagree with the copy nobody is looking at.

```bash
make k8s-sync    # refresh the chart's copies from the originals
make k8s-check   # fail if they have drifted   (runs in CI and before every helm target)
```

---

## The interview answer this is here for

> **"What happens to an in-flight Kafka message when a pod is evicted?"**
>
> The pod leaves its Service before `SIGTERM` because of the `preStop` hook, Spring gets 30 seconds
> to finish what is in flight, and anything unfinished never had its offset committed — the
> consumers ack manually, after the database transaction commits. The group rebalances, another pod
> reprocesses it, and reprocessing is safe: the event ID is deterministic, so the redelivery is
> recognisable, and the partial unique index on `correlation_key` means two consumers acting on the
> same key still produce one incident.
>
> The failure I care about is not a crash. It is two incidents where there should be one — and
> `make kind-drain` is the test that says there is not.
