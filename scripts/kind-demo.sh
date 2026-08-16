#!/usr/bin/env bash
set -euo pipefail

# Same sequence as `make kind-demo`, for machines without make (Windows/Git Bash).
#
# Compose is the primary path — `docker compose up -d --wait` is the two-minute demo and needs none of this.
# See k8s/README.md for what the Kubernetes deployment demonstrates and what it does not.

cd "$(dirname "$0")/.."

KIND_CLUSTER="${KIND_CLUSTER:-sentinel}"
K8S_NS="${K8S_NS:-sentinel}"
HELM_RELEASE="${HELM_RELEASE:-sentinel}"
CHART=./k8s/helm/sentinel

for tool in docker kind kubectl helm; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing required tool: $tool" >&2; exit 1; }
done

echo "==> Checking the chart's copies of infra/ and loadtest/ assets"
./scripts/k8s-sync.sh --check

echo "==> Creating the kind cluster"
kind create cluster --config k8s/kind-cluster.yaml || echo "  cluster already exists"
kubectl cluster-info --context "kind-${KIND_CLUSTER}"

# Side-loading, not pulling: the images are tagged :local and exist on no registry, so without this
# the kubelet sits in ImagePullBackOff looking for them on Docker Hub.
echo "==> Building images and loading them into the cluster"
docker build -f sentinel-platform/Dockerfile -t sentinel/platform:local .
docker build -f demo-fleet/Dockerfile -t sentinel/demo-fleet:local .
kind load docker-image sentinel/platform:local --name "${KIND_CLUSTER}"
kind load docker-image sentinel/demo-fleet:local --name "${KIND_CLUSTER}"

echo "==> helm upgrade --install"
helm upgrade --install "${HELM_RELEASE}" "${CHART}" \
  --namespace "${K8S_NS}" --create-namespace --wait --timeout 10m
kubectl -n "${K8S_NS}" rollout status "deploy/${HELM_RELEASE}" --timeout=5m

cat <<'EOF'

  ▶  Open http://localhost:3000  and press 'Start demo'

     Swagger:    http://localhost:3000/swagger-ui.html
     Grafana:    http://localhost:3001   (anonymous, no login)
     Prometheus: http://localhost:9090

  Probes:        kubectl -n sentinel get pods
                 kubectl -n sentinel exec deploy/sentinel -- \
                   wget -qO- localhost:8080/actuator/health/readiness
  Drain test:    ./scripts/k8s-drain-test.sh
  Tear down:     kind delete cluster --name sentinel

EOF
