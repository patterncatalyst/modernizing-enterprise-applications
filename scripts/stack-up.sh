#!/usr/bin/env bash
# scripts/stack-up.sh — bring up the r02 local infra/observability stack.
#
# PODMAN ONLY (DRQ-001). Uses the podman-compose-v2 plugin, never `docker
# compose` / `docker-compose`. Run from anywhere; paths are resolved relative
# to this script's location so it works from any cwd.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ ! -f .env ]; then
  echo "No .env found — copying .env.example (edit it if you need different pins/ports)." >&2
  cp .env.example .env
fi

echo "Starting stack (podman compose, --env-file .env)..."
podman compose --env-file .env up -d

echo
echo "Waiting for services to report healthy..."
for i in $(seq 1 60); do
  statuses=$(podman compose ps --format '{{.Health}}' 2>/dev/null || true)
  if [ -n "$statuses" ] && ! echo "$statuses" | grep -qv -E '^(healthy)?$'; then
    break
  fi
  sleep 2
done

podman compose ps
echo
echo "Grafana:    http://localhost:3000"
echo "Postgres:   localhost:${POSTGRES_PORT:-5432} (see .env for db/user/password)"
echo "Kafka:      localhost:${KAFKA_HOST_PORT:-9092} (host) / kafka:9094 (compose network)"
echo "Connect:    http://localhost:${CONNECT_HOST_PORT:-8086}/connectors (Debezium CDC — see infra/debezium/README.md)"
echo "            Register the inventory connector: scripts/register-debezium.sh"
