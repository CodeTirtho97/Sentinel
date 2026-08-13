.PHONY: help build test test-unit verify mutation coverage fmt fmt-check demo seed break break-both reset kill watch down logs clean ps \
        load-test load-test-up load-test-seed load-test-storm load-test-replay load-test-recovery load-test-down \
        kind-up kind-load kind-deploy kind-demo kind-status kind-drain kind-down helm-lint helm-template k8s-sync k8s-check

MVN := ./mvnw

# Everything the load test needs and nothing it does not. The demo fleet and the k6 baseline
# generator are excluded on purpose: eight extra JVMs competing for the same cores would mean
# measuring the laptop rather than the evaluator.
LOADTEST_COMPOSE := docker compose -f docker-compose.yml -f docker-compose.loadtest.yml
LOADTEST_SERVICES := postgres redpanda redis prometheus grafana synthetic-exporter sentinel
K6 := docker run --rm --network sentinel_default -v "$(CURDIR)/loadtest/k6:/scripts:ro" \
        -e SENTINEL=http://sentinel:8080 -e EXPORTER=http://synthetic-exporter:8080 grafana/k6:0.52.0

help:
	@echo "build      - compile + package all modules (skips tests)"
	@echo "test       - run everything: unit tests + Testcontainers integration suite"
	@echo "test-unit  - unit tests only; fast, needs no Docker"
	@echo "mutation   - PIT mutation testing on slo.math (the only package it runs on)"
	@echo "coverage   - JaCoCo report at sentinel-platform/target/site/jacoco/index.html"
	@echo "fmt        - apply spotless formatting"
	@echo "demo       - bring the stack up, then drive it from http://localhost:3000"
	@echo "seed       - create SLOs for all 8 services      (the UI's 'Start demo')"
	@echo "break      - break ledger-service, one cascade   (the UI's 'Break order path')"
	@echo "break-both - break both leaves, two incidents    (the UI's 'Break both paths')"
	@echo "reset      - clear all injected failure          (the UI's 'Reset')"
	@echo "kill       - halt Sentinel mid-incident          (the UI's 'Kill Sentinel')"
	@echo "watch      - live incident narration in the terminal"
	@echo "down       - stop the stack and remove volumes"
	@echo "logs       - tail sentinel-platform logs"
	@echo "clean      - mvn clean"
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

build:
	$(MVN) -q -DskipTests package

# Integration tests are *IT and run under failsafe, so they need Docker for Testcontainers.
test: verify

verify:
	$(MVN) verify

test-unit:
	$(MVN) test

# slo.math only. That package is pure arithmetic, so a surviving mutant is a real gap in the
# assertions rather than an artefact of something being hard to reach — and the boundary it guards
# (burn >= threshold, not >) is what makes CRITICAL fire at exactly 14.4.
mutation:
	$(MVN) -pl sentinel-platform test-compile org.pitest:pitest-maven:mutationCoverage
	@echo "Report: sentinel-platform/target/pit-reports/index.html"

coverage:
	$(MVN) -pl sentinel-platform verify
	@echo "Report: sentinel-platform/target/site/jacoco/index.html"

fmt:
	$(MVN) -q spotless:apply

fmt-check:
	$(MVN) -q spotless:check

# Brings the stack up and hands over to the visualiser. Seeding and chaos are buttons there, so the
# demo needs exactly one command and Docker Desktop running.
#
# The demo profile compresses the SLO windows so a cascade is visible inside two minutes.
demo: export SPRING_PROFILES_ACTIVE=demo
demo:
	docker compose up -d --build
	./scripts/wait-for-health.sh
	@echo ""
	@echo "  ▶  Open http://localhost:3000  and press 'Start demo'"
	@echo ""
	@echo "     Grafana:    http://localhost:3001  (anonymous access, no login)"
	@echo "     Prometheus: http://localhost:9090"
	@echo ""

# Everything the visualiser's buttons do, for anyone who prefers a terminal.
seed:
	./scripts/seed-slos.sh
break:
	./scripts/inject-cascade.sh
break-both:
	./scripts/inject-two-failures.sh
reset:
	./scripts/reset-chaos.sh
# Halts the process with an open incident. Docker restarts it; the incident must come back with no
# duplicates. Demo profile only — the endpoint does not exist otherwise.
kill:
	curl -fsS -X POST -H "X-Api-Key: $${SENTINEL_API_KEY:-local-dev-key}" \
		http://localhost:3000/api/v1/demo/kill && echo ""
watch:
	./scripts/watch-incidents.sh

# ---------------------------------------------------------------------------
# Load testing. See loadtest/README.md and docs/LOAD_TEST_RESULTS.md.
# ---------------------------------------------------------------------------

# The full ramp: 100 / 250 / 500 / 1000 / 2000 services, ~10 minutes of sampling each plus
# restarts. Override with SIZES and DURATION_MIN for something shorter.
load-test:
	./scripts/load-test.sh

load-test-up:
	SYNTHETIC_SERVICES=$(or $(SYNTHETIC_SERVICES),100) $(LOADTEST_COMPOSE) up -d --build $(LOADTEST_SERVICES)
	./scripts/wait-for-health.sh
	@echo ""
	@echo "  Exporter: http://localhost:8089/status"
	@echo "  Next:     make load-test-seed"

# Nothing is evaluated until SLOs exist. Skipping this measures an empty cycle.
load-test-seed:
	$(K6) run /scripts/seed-synthetic-slos.js

load-test-storm:
	$(K6) run -e FRACTION=$(or $(FRACTION),0.3) /scripts/breach-storm.js

load-test-replay:
	$(K6) run -e COUNT=$(or $(COUNT),10000) /scripts/duplicate-replay.js

load-test-recovery:
	./scripts/recovery-test.sh

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
