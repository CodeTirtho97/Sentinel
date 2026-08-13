#!/usr/bin/env bash
set -euo pipefail

# Helm cannot read files above the chart root, so three assets the Compose stack owns have to exist
# a second time inside k8s/helm/sentinel/files. Duplication that nothing enforces is duplication
# that rots — this script is the enforcement.
#
#   ./scripts/k8s-sync.sh            copy infra/ and loadtest/ into the chart
#   ./scripts/k8s-sync.sh --check    fail if the copies have drifted (used by CI and `make helm-lint`)

cd "$(dirname "$0")/.."

CHART_FILES="k8s/helm/sentinel/files"

# source:destination
PAIRS=(
  "infra/prometheus/rules/slo-recording-rules.yml:$CHART_FILES/slo-recording-rules.yml"
  "infra/grafana/provisioning/dashboards/fleet-slo.json:$CHART_FILES/dashboards/fleet-slo.json"
  "infra/grafana/provisioning/dashboards/sentinel-internals.json:$CHART_FILES/dashboards/sentinel-internals.json"
  "loadtest/k6/baseline.js:$CHART_FILES/baseline.js"
)

check_mode=false
if [[ "${1:-}" == "--check" ]]; then
  check_mode=true
fi

drifted=0

for pair in "${PAIRS[@]}"; do
  src="${pair%%:*}"
  dst="${pair##*:}"

  if [[ ! -f "$src" ]]; then
    echo "missing source: $src" >&2
    exit 1
  fi

  if $check_mode; then
    if [[ ! -f "$dst" ]] || ! diff -q "$src" "$dst" >/dev/null 2>&1; then
      echo "  DRIFT  $src -> $dst"
      drifted=1
    fi
  else
    mkdir -p "$(dirname "$dst")"
    cp "$src" "$dst"
    echo "  synced $src -> $dst"
  fi
done

if $check_mode; then
  if (( drifted )); then
    echo "" >&2
    echo "The Helm chart's copies are out of date. Run: make k8s-sync" >&2
    exit 1
  fi
  echo "chart assets match infra/ and loadtest/"
fi
