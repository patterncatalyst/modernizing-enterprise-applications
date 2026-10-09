#!/usr/bin/env bash
# scripts/stack-down.sh — tear down the r02 local infra/observability stack.
#
# Docker Engine + `docker compose` (DRQ-077). By default preserves named volumes (postgres-data,
# kafka-data) so seed data / topics survive a restart. Pass -v to wipe them
# (e.g. to recover from the Kafka KRaft cluster-ID mismatch described in
# known-issues.md).
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

if [ "${1:-}" = "-v" ]; then
  echo "Stopping stack and wiping volumes (postgres-data, kafka-data)..."
  docker compose --env-file .env down -v
else
  echo "Stopping stack (volumes preserved; pass -v to wipe them)..."
  docker compose --env-file .env down
fi
