#!/usr/bin/env bash
# demo-equivalence.sh — run the behavior-equivalence suite (r02-plan S6).
#
# This collection (tooling/newman/mea.postman_collection.json) is the project's
# EQUIVALENCE GATE (build-plan.md §G, DRQ-014): it was captured against the
# running Spring Boot monolith and is re-run UNCHANGED against every extracted
# service. A service is "done" only when it passes this same suite with no
# edits to the collection — only the target --baseUrl changes.
#
# Usage:
#   demos/demo-equivalence.sh [baseUrl]
#   demos/demo-equivalence.sh                         # defaults to the monolith baseline (http://localhost:8080)
#   demos/demo-equivalence.sh http://localhost:8080    # explicit monolith baseline
#   demos/demo-equivalence.sh http://localhost:8081    # the extracted Review service, once it exists (S8+)
#   demos/demo-equivalence.sh http://localhost:8888    # the FINISHED system, through the edge router
#
# NOTE on the default: the monolith is DECOMMISSIONED in the finished system, so
# the :8080 default now points at a process a fresh checkout must bring up on
# purpose (the frozen examples/00-monolith) — it remains valid only as the
# golden-baseline referent the suite was captured from. For the finished system,
# point this at the edge router: `demos/demo-equivalence.sh http://localhost:8888`
# (and see demos/demo-final-topology.sh, which runs the full suite end-to-end
# through :8888 with the saga + GraphQL gate variables enabled).
#
# Prerequisites: the target service must already be running and reachable at
# --baseUrl, with its database migrated and seed data loaded. This script does
# NOT start/stop the service it tests — that is the caller's responsibility
# (see tooling/newman/README.md for how to bring up the monolith locally).
#
# Requires: newman (npm i -g newman), or run via npx.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
COLLECTION="${PROJECT_ROOT}/tooling/newman/mea.postman_collection.json"
ENVIRONMENT="${PROJECT_ROOT}/tooling/newman/local.postman_environment.json"

BASE_URL="${1:-http://localhost:8080}"

if ! command -v newman >/dev/null 2>&1; then
    echo "error: newman is not installed or not on PATH." >&2
    echo "       install it with: npm install -g newman" >&2
    exit 1
fi

echo "== Behavior-equivalence suite ==================================="
echo "collection : ${COLLECTION}"
echo "environment: ${ENVIRONMENT} (baseUrl overridden to ${BASE_URL})"
echo "==================================================================="

newman run "${COLLECTION}" \
    --environment "${ENVIRONMENT}" \
    --env-var "baseUrl=${BASE_URL}" \
    --reporters cli
