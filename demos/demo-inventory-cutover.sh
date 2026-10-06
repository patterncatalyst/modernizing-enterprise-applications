#!/usr/bin/env bash
# demos/demo-inventory-cutover.sh — demonstrate the Inventory context (ch.19,
# Extraction 3) standing on its own behind the finished edge router.
#
# WHAT THIS IS, ON `main`: the proxy on `main` is a FLAGLESS REST edge router
# (order-plan.md DRQ-070) — the six strangler.*.enabled cutover flags are
# retired and /api/inventory routes unconditionally to the extracted inventory
# service. So on `main` this script proves what CAN be proven here: the Inventory
# Context Contract served through the edge router (:8888), and the service-down
# negative check that makes that routing non-vacuous.
#
# WHAT THIS IS NOT, ON `main`: the flag-flip CUTOVER and the reversibility window
# ch.19 narrates (strangler.inventory.enabled true→false, /api/inventory served
# by the monolith again) CANNOT be reconstructed against the flagless edge
# router. To run that, check out the matching snapshot where the proxy still
# carried its cutover flags:
#     git checkout stage/03-inventory-extracted
# and read ch.19's "cutover" and "reversibility" sections alongside it.
#
# Prerequisites: the podman stack (mea-postgres, mea-kafka) must be reachable —
# this script runs scripts/stack-up.sh if it isn't already up, but will NOT tear
# it down. Every app process this script starts is stopped on exit; the podman
# stack is always left running.
#
# Usage: demos/demo-inventory-cutover.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

INVENTORY_JAR="examples/04-inventory-service/target/quarkus-app/quarkus-run.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"

LOG_DIR="$(mktemp -d /tmp/demo-inventory-cutover.XXXXXX)"
declare -A PIDS=()
OVERALL_RESULT=0

pass() { echo "  OK   $*"; }
fail() { echo "  FAIL $*" >&2; OVERALL_RESULT=1; }
section() { echo; echo "== $* =========================================================="; }

cleanup() {
    section "Cleanup — stopping everything this script started (podman stack left running)"
    for name in "${!PIDS[@]}"; do
        pid="${PIDS[$name]}"
        if kill -0 "${pid}" 2>/dev/null; then
            kill "${pid}" 2>/dev/null
            echo "  stopped ${name} (pid ${pid})"
        fi
    done
    sleep 2
    for name in "${!PIDS[@]}"; do
        pid="${PIDS[$name]}"
        kill -9 "${pid}" 2>/dev/null || true
    done
    echo "Logs kept at ${LOG_DIR}"
}
trap cleanup EXIT

wait_for() {
    local url="$1" label="$2" attempts=30
    for ((i = 1; i <= attempts; i++)); do
        if curl -s -o /dev/null --max-time 2 "${url}"; then
            pass "${label} is up (${url})"
            return 0
        fi
        sleep 1
    done
    fail "${label} never came up at ${url}"
    return 1
}

start_jar() {
    local name="$1" jar="$2" health="$3"
    shift 3
    echo "starting ${name}: $* java -jar ${jar}"
    env "$@" nohup java -jar "${jar}" > "${LOG_DIR}/${name}.log" 2>&1 &
    PIDS["${name}"]=$!
    wait_for "${health}" "${name}"
}

stop_named() {
    local name="$1"
    local pid="${PIDS[$name]:-}"
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
        kill "${pid}"
        for i in $(seq 1 10); do
            kill -0 "${pid}" 2>/dev/null || break
            sleep 1
        done
        kill -9 "${pid}" 2>/dev/null || true
    fi
    unset "PIDS[$name]"
}

run_folder() {
    # run_folder <base-url> <folder-name> -> exit 0 iff newman reported 0 failures
    local base="$1" folder="$2"
    npx newman run "${PROJECT_ROOT}/tooling/newman/mea.postman_collection.json" \
        --environment "${PROJECT_ROOT}/tooling/newman/local.postman_environment.json" \
        --env-var "baseUrl=${base}" \
        --folder "${folder}" \
        --reporters cli
}

section "0. Podman stack (Postgres + Kafka)"
if podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the inventory service + the flagless edge router (no monolith needed — the contract is reads)"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
# The edge router is started plain: there are no strangler.*.enabled flags left;
# /api/inventory routes unconditionally to the inventory service above.
start_jar edge-router "${PROXY_JAR}" "http://localhost:8888/api/inventory"

section "2. CONTRACT — the Inventory Context Contract folder through the edge router (:8888)"
if run_folder "http://localhost:8888" "Inventory Context Contract"; then
    pass "Inventory Context Contract GREEN through the edge router"
else
    fail "Inventory Context Contract had failures through the edge router"
fi

section "2b. Routing proof — the edge router's reply is byte-identical to the service's own"
sku="SKU-WIDGET-001"
via_router="$(curl -s "http://localhost:8888/api/inventory/${sku}")"
via_service="$(curl -s "http://localhost:8084/api/inventory/${sku}")"
echo "  via edge router : ${via_router}"
echo "  via service     : ${via_service}"
if [ -n "${via_service}" ] && [ "${via_router}" = "${via_service}" ]; then
    pass "edge router returns exactly what the inventory service returns for /api/inventory/${sku}"
else
    fail "edge router reply did not match the inventory service's direct reply"
fi

section "3. NEGATIVE CHECK — stop the inventory service, the same folder MUST go RED"
stop_named inventory
echo "inventory service stopped — re-running the Inventory Context Contract folder, expecting RED..."
if run_folder "http://localhost:8888" "Inventory Context Contract"; then
    fail "expected RED with the inventory service down, but the folder passed — the edge route is not genuinely reaching the service"
else
    pass "RED as expected — with the inventory service down, the contract reads fail through the edge router"
fi

echo "restarting the inventory service..."
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
echo "re-running the Inventory Context Contract folder, expecting GREEN..."
if run_folder "http://localhost:8888" "Inventory Context Contract"; then
    pass "GREEN again — the contract recovered once the inventory service came back"
else
    fail "expected GREEN after restarting the inventory service, but the folder still failed"
fi

section "Demo complete"
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: the inventory context serves its contract through the edge router, and the route is non-vacuous (RED when the service is down)."
else
    echo "RESULT: one or more proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "For the flag-flip cutover + reversibility window (ch.19), run from stage/03-inventory-extracted."
echo "=================================================================="
exit "${OVERALL_RESULT}"
