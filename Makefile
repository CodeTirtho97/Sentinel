# This file holds only what does not fit on one line.
#
# Running the demo and building the project are single commands that work identically on Windows,
# macOS and Linux, so wrapping them here bought nothing and cost a `make` dependency that Windows
# does not ship. They live in README.md instead. What remains is genuine multi-step orchestration:
# the load-test measurement sequence and the kind/Helm path.
#
#   docker compose up -d --wait     start the demo stack
#   ./scripts/demo.sh               terminal control for it
#   ./mvnw verify                   build and test
.PHONY: help \
        load-test load-test-up load-test-seed load-test-storm load-test-replay load-test-recovery load-test-down \
        kind-up kind-load kind-deploy kind-demo kind-status kind-drain kind-down helm-lint helm-template k8s-sync k8s-check

MVN := ./mvnw

# Everything the load test needs and nothing it does not. The demo fleet and the k6 baseline
# generator are excluded on purpose: eight extra JVMs competing for the same cores would mean
# measuring the laptop rather than the evaluator.
LOADTEST_COMPOSE := docker compose -f docker-compose.yml -f docker-compose.loadtest.yml
# Scratch file every measurement appends to. docs/LOAD_TEST_RESULTS.md is the record, this is the
# tape it gets transcribed from.
RAW := docs/LOAD_TEST_RESULTS.raw.md
LOADTEST_SERVICES := postgres redpanda redis prometheus grafana synthetic-exporter sentinel
# MSYS_NO_PATHCONV stops Git Bash rewriting the container-side paths in -v and turning the mount
# into an empty directory. Harmless everywhere else.
K6 := MSYS_NO_PATHCONV=1 docker run --rm --network sentinel_default -v "$(CURDIR)/loadtest/k6:/scripts:ro" \
        -e SENTINEL=http://sentinel:8080 -e EXPORTER=http://synthetic-exporter:8080 grafana/k6:0.52.0

help:
	@echo "The demo and the build need no make — see README.md:"
	@echo "  docker compose up -d --wait   start the stack, wait until healthy"
	@echo "  ./scripts/demo.sh             seed / break / reset / kill / status"
	@echo "  ./scripts/watch-incidents.sh  live incident narration"
	@echo "  ./mvnw verify                 build + unit + integration tests"
	@echo ""
	@echo "load testing (see loadtest/README.md):"
	@echo "  load-test          - the full evaluation-throughput ramp; takes hours"
	@echo "  load-test-up       - stack + synthetic exporter, no demo fleet"
	@echo "  load-test-seed     - two SLOs per synthetic service; run before any measurement"
	@echo "  load-test-storm    - breach storm + alert collapse ratio"
	@echo "  load-test-replay   - 10,000 duplicate events, expect zero duplicate incidents"
	@echo "  load-test-recovery - kill mid-cycle, measure time to steady state"
	@echo "  load-test-down     - tear the load-test stack down"
	@echo ""
	@echo "kubernetes — the secondary path (see k8s/README.md):"
	@echo "  kind-demo    - nothing to a running stack on kind: cluster, images, helm install"
	@echo "  kind-up      - create the kind cluster only"
	@echo "  kind-load    - build the images and side-load them into the cluster"
	@echo "  kind-deploy  - helm upgrade --install"
	@echo "  kind-status  - pods, and what each probe currently says"
	@echo "  kind-drain   - delete the sentinel pod mid-stream; drain must lose nothing"
	@echo "  kind-down    - delete the cluster"
	@echo "  helm-lint    - lint and render the chart; needs no cluster"
	@echo "  k8s-sync     - copy shared assets from infra/ and loadtest/ into the chart"
	@echo "  k8s-check    - fail if those copies have drifted"

# ---------------------------------------------------------------------------
# Load testing. See loadtest/README.md and docs/LOAD_TEST_RESULTS.md.
# ---------------------------------------------------------------------------

# The full ramp: 100 / 250 / 500 services, 20 minutes of sampling each plus restarts — ~75 min.
# Three points establish a shape rather than a pair of dots, and 20 minutes is ~80 cycles, enough
# that the p99 is not one outlier wearing a hat. Override with SIZES and DURATION_MIN; do the
# smoke run first: SIZES="100" DURATION_MIN=3 ./scripts/load-test.sh
load-test:
	./scripts/load-test.sh

# SKIP_FLEET=1 because the overlay deliberately does not start the eight demo-fleet JVMs; waiting
# on them would be eight consecutive 300s timeouts and a failed run.
load-test-up:
	SYNTHETIC_SERVICES=$(or $(SYNTHETIC_SERVICES),100) $(LOADTEST_COMPOSE) up -d --build $(LOADTEST_SERVICES)
	SKIP_FLEET=1 ./scripts/wait-for-health.sh
	@echo ""
	@echo "  Services: $(or $(SYNTHETIC_SERVICES),100) synthetic"
	@echo "  Exporter: http://localhost:8089/status"
	@echo "  Sentinel: http://localhost:3000/actuator/prometheus"
	@echo "  Next:     make load-test-seed"

# Nothing is evaluated until SLOs exist. Skipping this measures an empty cycle.
load-test-seed:
	$(K6) run /scripts/seed-synthetic-slos.js
	@echo ""
	@echo "  SLOs now present: $$(curl -fsS -H 'X-Api-Key: local-dev-key' http://localhost:3000/api/v1/slos | grep -o '\"serviceName\"' | wc -l | tr -d ' ')"

# Measurements 2-5 append to the raw file as well as printing. A number that exists only in a
# terminal you later close is a number you have to measure again.
load-test-storm:
	@mkdir -p docs
	@printf '\n## Breach storm + alert collapse (FRACTION=%s) — %s\n\n```\n' "$(or $(FRACTION),0.3)" "$$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> $(RAW)
	$(K6) run -e FRACTION=$(or $(FRACTION),0.3) /scripts/breach-storm.js 2>&1 | tee -a $(RAW)
	@printf '```\n' >> $(RAW)
	@echo "  appended to $(RAW)"

load-test-replay:
	@mkdir -p docs
	@printf '\n## Duplicate replay (COUNT=%s) — %s\n\n```\n' "$(or $(COUNT),10000)" "$$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> $(RAW)
	$(K6) run -e COUNT=$(or $(COUNT),10000) /scripts/duplicate-replay.js 2>&1 | tee -a $(RAW)
	@printf '```\n' >> $(RAW)
	@echo "  appended to $(RAW)"

load-test-recovery:
	@mkdir -p docs
	@printf '\n## Recovery — %s\n\n```\n' "$$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> $(RAW)
	./scripts/recovery-test.sh 2>&1 | tee -a $(RAW)
	@printf '```\n' >> $(RAW)
	@echo "  appended to $(RAW)"

load-test-down:
	$(LOADTEST_COMPOSE) down -v

# ---------------------------------------------------------------------------
# Kubernetes. The secondary path — Compose stays primary, and nothing here is required to run the
# demo. See k8s/README.md for what this does and does not demonstrate.
# ---------------------------------------------------------------------------

KIND_CLUSTER := sentinel
K8S_NS       := sentinel
HELM_RELEASE := sentinel
CHART        := ./k8s/helm/sentinel

# Nothing to a working stack. Three steps rather than one target so a failure says which.
kind-demo: kind-up kind-load kind-deploy
	@echo ""
	@echo "  ▶  Open http://localhost:3000  and press 'Start demo'"
	@echo ""
	@echo "     Grafana:    http://localhost:3001"
	@echo "     Prometheus: http://localhost:9090"
	@echo ""

kind-up:
	kind create cluster --config k8s/kind-cluster.yaml || echo "cluster already exists"
	kubectl cluster-info --context kind-$(KIND_CLUSTER)

# Side-loading, not pulling. The images are built locally and tagged :local, which exists on no
# registry — without this the kubelet sits in ImagePullBackOff looking for it on Docker Hub.
kind-load:
	docker build -f sentinel-platform/Dockerfile -t sentinel/platform:local .
	docker build -f demo-fleet/Dockerfile -t sentinel/demo-fleet:local .
	kind load docker-image sentinel/platform:local --name $(KIND_CLUSTER)
	kind load docker-image sentinel/demo-fleet:local --name $(KIND_CLUSTER)

kind-deploy: k8s-check
	helm upgrade --install $(HELM_RELEASE) $(CHART) \
		--namespace $(K8S_NS) --create-namespace --wait --timeout 10m
	kubectl -n $(K8S_NS) rollout status deploy/$(HELM_RELEASE) --timeout=5m

# What each probe actually says, which is the whole point of the phase.
kind-status:
	kubectl -n $(K8S_NS) get pods -o wide
	@echo ""
	@echo "readiness:"
	@kubectl -n $(K8S_NS) exec deploy/$(HELM_RELEASE) -- \
		wget -qO- http://localhost:8080/actuator/health/readiness || true
	@echo ""
	@echo "liveness:"
	@kubectl -n $(K8S_NS) exec deploy/$(HELM_RELEASE) -- \
		wget -qO- http://localhost:8080/actuator/health/liveness || true
	@echo ""

# The Phase 4 acceptance test. Delete the pod while breaches are in flight: the offset of anything
# uncommitted is never committed, the group rebalances, the replacement reprocesses, and the
# idempotency layers make reprocessing a no-op. Incident count before and after must match.
kind-drain:
	./scripts/k8s-drain-test.sh

kind-down:
	kind delete cluster --name $(KIND_CLUSTER)

# Renders every template with both the default values and the sharded ones. Catches the majority of
# chart mistakes without waiting five minutes for a cluster.
helm-lint: k8s-check
	helm lint $(CHART)
	helm lint $(CHART) --set sentinel.sharding.enabled=true --set keda.enabled=true
	helm template $(HELM_RELEASE) $(CHART) --namespace $(K8S_NS) >/dev/null
	helm template $(HELM_RELEASE) $(CHART) --namespace $(K8S_NS) \
		--set sentinel.sharding.enabled=true --set keda.enabled=true >/dev/null
	@echo "chart renders clean"

helm-template:
	helm template $(HELM_RELEASE) $(CHART) --namespace $(K8S_NS)

# Helm cannot read files above the chart root, so the recording rules, the dashboards and the
# baseline k6 script exist twice. These two targets are what keeps the copy honest: k8s-sync
# refreshes it, k8s-check fails the build if someone edited the original and forgot.
k8s-sync:
	./scripts/k8s-sync.sh

k8s-check:
	./scripts/k8s-sync.sh --check

down:
	docker compose down -v

ps:
	docker compose ps

logs:
	docker compose logs -f sentinel

clean:
	$(MVN) -q clean
