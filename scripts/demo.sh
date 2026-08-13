#!/usr/bin/env bash
set -euo pipefail

# Same sequence as `make demo`, for machines without make (Windows/Git Bash).

cd "$(dirname "$0")/.."

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-demo}"

echo "==> Building and starting the stack (first run pulls images and builds jars)"
docker compose up -d --build

./scripts/wait-for-health.sh
./scripts/seed-slos.sh

echo "==> Starting the baseline load generator (~20 rps against checkout-service)"
docker compose up -d loadgen

cat <<'EOF'

  Demo console: http://localhost:3000
  Swagger:      http://localhost:3000/swagger-ui.html
  Grafana:      http://localhost:3001/d/sentinel-fleet-slo   (anonymous, no login)
  Prometheus:   http://localhost:9090

  /api/v1 needs a key. In Swagger press Authorize; from a terminal:
    curl -H 'X-Api-Key: local-dev-key' http://localhost:3000/api/v1/incidents

EOF

echo "==> Letting the windows fill, then injecting a cascading failure in 20s..."
sleep 20
./scripts/inject-cascade.sh
