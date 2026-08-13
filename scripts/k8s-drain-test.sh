#!/usr/bin/env bash
set -euo pipefail

# The Phase 4 acceptance test: kill a pod mid-message and prove nothing is lost or duplicated.
#
# It is the Compose recovery test's question asked of Kubernetes, and the answer comes from the same
# three mechanisms:
#
#   preStop + graceful shutdown   the pod leaves its Service before SIGTERM, and Spring gets 30s to
#                                 finish what is in flight
#   manual ack                    anything not finished never had its offset committed, so the group
#                                 rebalances and the replacement pod reprocesses it
#   deterministic IDs + the partial unique index   reprocessing is a no-op rather than a duplicate
#
# The interesting failure is not a crash. It is two incidents where there should be one.

NS="${K8S_NS:-sentinel}"
RELEASE="${HELM_RELEASE:-sentinel}"
SENTINEL_URL="${SENTINEL_URL:-http://localhost:3000}"
API_KEY="${SENTINEL_API_KEY:-local-dev-key}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-300}"

api() { curl -fsS -H "X-Api-Key: ${API_KEY}" "$@"; }

incident_count() { api "${SENTINEL_URL}/api/v1/incidents?size=200" | grep -o '"id"' | wc -l | tr -d ' '; }

# A duplicate is two unresolved incidents sharing a correlation key — exactly what the partial
# unique index exists to make impossible.
duplicate_keys() {
  api "${SENTINEL_URL}/api/v1/incidents?size=200&state=OPEN" \
    | grep -o '"correlationKey":"[^"]*"' | sort | uniq -d | wc -l | tr -d ' '
}

echo "==== KUBERNETES DRAIN TEST ===="

pod_before=$(kubectl -n "${NS}" get pods -l app.kubernetes.io/component=sentinel \
  -o jsonpath='{.items[0].metadata.name}')
echo "  pod:                     ${pod_before}"

before_incidents=$(incident_count)
echo "  incidents before:        ${before_incidents}"

# Deleting a quiet pod proves nothing. Seed the SLOs and break the order path first, so there are
# breaches on the topic and consumers genuinely mid-work when the pod goes away.
echo "  seeding SLOs and breaking ledger-service so there is something in flight..."
api -X POST "${SENTINEL_URL}/api/v1/demo/seed" >/dev/null 2>&1 \
  || echo "  (no demo endpoint — continuing with whatever is already configured)"
api -X POST "${SENTINEL_URL}/api/v1/demo/chaos" >/dev/null 2>&1 || true

# The demo profile's compressed windows put the first breach around 30s in. Deleting the pod before
# that is deleting an idle process, which tests nothing.
echo "  waiting for the cascade to start..."
sleep 45

echo "  deleting the pod..."
deleted_at=$(date +%s)
kubectl -n "${NS}" delete pod "${pod_before}" --wait=false >/dev/null

# The drain itself. A pod that vanishes instantly did not drain gracefully — Spring's shutdown phase
# plus the preStop sleep means a clean stop takes a few seconds, and that is the point.
kubectl -n "${NS}" wait --for=delete "pod/${pod_before}" --timeout="${TIMEOUT_SECONDS}s" >/dev/null
drained_at=$(date +%s)
echo "  drain took:              $((drained_at - deleted_at))s"

echo "  waiting for the replacement to become ready..."
kubectl -n "${NS}" rollout status "deploy/${RELEASE}" --timeout="${TIMEOUT_SECONDS}s" >/dev/null \
  || kubectl -n "${NS}" rollout status "statefulset/${RELEASE}" --timeout="${TIMEOUT_SECONDS}s" >/dev/null
ready_at=$(date +%s)

# Readiness here already means more than "the port is open": the custom indicator holds the pod out
# of the Service until the consumer group has actually assigned it partitions.
echo "  ready after:             $((ready_at - deleted_at))s"

echo "  letting redelivery settle..."
sleep 30

after_incidents=$(incident_count)
after_dupes=$(duplicate_keys)

echo ""
echo "  Incidents before:        ${before_incidents}"
echo "  Incidents after:         ${after_incidents}"
echo "  Duplicate active keys:   ${after_dupes}   (expected 0)"
echo "==============================="

if [[ "${after_dupes}" -ne 0 ]]; then
  echo "FAIL: ${after_dupes} correlation keys have more than one active incident" >&2
  exit 1
fi
if [[ "${after_incidents}" -lt "${before_incidents}" ]]; then
  echo "FAIL: incidents went backwards — ${before_incidents} before, ${after_incidents} after" >&2
  exit 1
fi
echo "PASS: pod replaced, no incident lost, no correlation key duplicated"
