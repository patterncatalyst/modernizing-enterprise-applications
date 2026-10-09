#!/usr/bin/env bash
# scripts/stack-up.sh — bring up the r02 local infra/observability stack.
#
# Docker Engine + the `docker compose` v2 plugin (DRQ-085, superseding the
# podman-only DRQ-001); never the legacy `docker-compose` v1 binary. Run from
# anywhere; paths are resolved relative to this script's location so it works
# from any cwd.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ ! -f .env ]; then
  echo "No .env found — copying .env.example (edit it if you need different pins/ports)." >&2
  cp .env.example .env
fi

command -v docker >/dev/null || { echo "docker not found: install Docker Engine (docker-ce) — see the Prerequisites chapter." >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "the docker compose v2 plugin is missing: sudo dnf install docker-compose-plugin" >&2; exit 1; }

echo "Starting stack (docker compose, --env-file .env)..."
docker compose --env-file .env up -d

echo
echo "Waiting for services to report healthy..."
for i in $(seq 1 60); do
  statuses=$(docker compose ps --format '{{.Health}}' 2>/dev/null || true)
  if [ -n "$statuses" ] && ! echo "$statuses" | grep -qv -E '^(healthy)?$'; then
    break
  fi
  sleep 2
done

docker compose ps
echo
echo "Grafana:    http://localhost:3000"
echo "Prometheus: http://localhost:${PROMETHEUS_HOST_PORT:-19090} (host 19090; Cockpit owns 9090)"
echo "Postgres:   localhost:${POSTGRES_PORT:-5432} (see .env for db/user/password)"
echo "Kafka:      localhost:${KAFKA_HOST_PORT:-9092} (host) / kafka:9094 (compose network)"
echo "Connect:    http://localhost:${CONNECT_HOST_PORT:-8086}/connectors (Debezium CDC — see infra/debezium/README.md)"
echo "            Register the inventory connector: scripts/register-debezium.sh"
