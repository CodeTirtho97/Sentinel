#!/usr/bin/env bash
# Single source of truth for the fleet's names and ports.
#
# Sourced by the other scripts so a topology change is one edit rather than five. Kept in sync by
# hand with docker-compose.yml and sentinel.dependencies in application.yml.

# service:host-port, in dependency order (entry points first, leaves last).
FLEET=(
  # Order path
  "api-gateway:8081"
  "checkout-service:8082"
  "cart-service:8083"
  "payment-service:8084"
  "fraud-service:8085"
  "ledger-service:8086"
  # Browse path
  "search-service:8087"
  "catalog-service:8088"
)

fleet_services() {
  local entry
  for entry in "${FLEET[@]}"; do
    echo "${entry%%:*}"
  done
}

fleet_ports() {
  local entry
  for entry in "${FLEET[@]}"; do
    echo "${entry##*:}"
  done
}

# Host port for a service name, or empty if it is not in the fleet.
fleet_port_of() {
  local wanted="$1" entry
  for entry in "${FLEET[@]}"; do
    if [[ "${entry%%:*}" == "$wanted" ]]; then
      echo "${entry##*:}"
      return 0
    fi
  done
  return 1
}
