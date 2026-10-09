#!/usr/bin/env bash
# demo-shipping-cutover.sh — demonstrate the Shipping orchestrated-saga
# CUTOVER (shipping-plan.md S8, ch.24, DRQ-059/060/062/065).
#
# NOTE: this reconstructs an INTERMEDIATE migration state and brings up the
# frozen examples/00-monolith (:8080) to show the cutover and its reversibility
# window against it. In the FINISHED system the monolith is decommissioned —
# see demos/demo-final-topology.sh for the end-state (flagless edge router, no
# monolith).
#
# It also assumes the PRE-DECOMMISSION proxy that still honours the
# strangler.*.enabled flags. On `main` the proxy is a flagless edge router
# (DRQ-070), so the flag flips below are no-ops there and the reversibility
# section cannot route back to the monolith — run this from the matching
# snapshot: `git checkout stage/05-shipping-extracted`.
#
# Mirrors demos/demo-payment-cutover.sh's shape (payment, r06/S8): this
# script brings up the whole topology itself, flips both cutover flags, and
# runs the proofs shipping-plan.md S8 requires:
#
#   1. CUTOVER        — shipping.mode=orchestrated (monolith) +
#                        strangler.shipping.enabled=true (proxy), full
#                        behavior-equivalence suite through the proxy
#                        (:8888), with the S2 Scenario 4 (Shipping-Failure)
#                        + Shipping Context Contract folders ENABLED.
#   2. REVERSIBILITY   — both flags flipped back; full suite green again,
#                        with checkout back to in-process confirm+dispatch
#                        on payment.captured (Scenario 4 pending again).
#   3. NEGATIVE CHECK a (DRQ-065) — in the cutover state, the Camel Saga
#                        EIP's `.compensation(...)` wiring is temporarily
#                        disabled in examples/06-shipping-service, the
#                        service is rebuilt+restarted, and a forced
#                        SHIP-FAIL order's Scenario 4 folder MUST go RED
#                        (order never reaches SHIPPING_FAILED, stock stays
#                        decremented). The source is then restored
#                        BYTE-FOR-BYTE (verified with `git status`, never
#                        used to perform the revert itself — a plain file
#                        copy does that), rebuilt, and Scenario 4 confirmed
#                        GREEN again.
#   4. NEGATIVE CHECK b (DRQ-065) — the shipping service (its sole
#                        `payment.captured` consumer) is stopped; a fresh
#                        order gets stuck AWAITING_SHIPMENT and Scenario 1's
#                        bounded-wait MUST go RED; restarting it drains the
#                        backlog and the same check goes GREEN again.
#
# IMPORTANT — why this script does NOT just pass `--env-var
# "shippingSagaEnabled=true"` to the newman CLI (a documented, empirically
# confirmed finding, not an assumption): the collection's SF-gate item reads
# `pm.collectionVariables.get('shippingSagaEnabled')`, and newman's
# `--env-var`/`--global-var` CLI flags only populate the ENVIRONMENT/GLOBAL
# variable scopes — NOT the collection-variable scope a collection's own
# `variable` array lives in. A CLI-only run with `--env-var
# "shippingSagaEnabled=true"` was verified (by hand, ahead of writing this
# script) to leave the SF-gate PENDING. This script instead loads the
# collection JSON into memory via the newman Node API, patches the
# IN-MEMORY `shippingSagaEnabled` variable's value, and hands the in-memory
# object (never a re-written file) to `newman.run()` — the committed
# collection file on disk is never touched. See
# examples/06-shipping-service/CUTOVER.md for the full writeup.
#
# Prerequisites: the compose stack (mea-postgres, mea-kafka) must be
# reachable — this script will run scripts/stack-up.sh if it isn't already
# up, but will NOT tear the stack down afterwards. All services this script
# itself starts (inventory/payment/review/notification/shipping/monolith/
# proxy) are stopped on exit; the compose stack is always left running.
#
# Usage: demos/demo-shipping-cutover.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

INVENTORY_JAR="examples/04-inventory-service/target/quarkus-app/quarkus-run.jar"
PAYMENT_JAR="examples/05-payment-service/target/quarkus-app/quarkus-run.jar"
REVIEW_JAR="examples/02-review-service/target/quarkus-app/quarkus-run.jar"
NOTIFICATION_JAR="examples/03-notification-service/target/quarkus-app/quarkus-run.jar"
SHIPPING_JAR="examples/06-shipping-service/target/quarkus-app/quarkus-run.jar"
MONOLITH_JAR="examples/00-monolith/target/monolith.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"
SAGA_ROUTE_FILE="examples/06-shipping-service/src/main/java/dev/patterncatalyst/shipping/ShipmentSagaRoute.java"

LOG_DIR="$(mktemp -d /tmp/demo-shipping-cutover.XXXXXX)"
declare -A PIDS=()
OVERALL_RESULT=0

pass() { echo "  OK   $*"; }
fail() { echo "  FAIL $*" >&2; OVERALL_RESULT=1; }
section() { echo; echo "== $* =========================================================="; }

cleanup() {
    section "Cleanup — stopping everything this script started (compose stack left running)"
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
    # Safety net: if the negative-check backup was left behind by a script
    # failure partway through, restore it and never leave the working tree
    # dirty.
    if [ -f "${SAGA_ROUTE_FILE}.orig-backup" ]; then
        echo "  restoring ${SAGA_ROUTE_FILE} from backup left by an interrupted run"
        cp "${SAGA_ROUTE_FILE}.orig-backup" "${SAGA_ROUTE_FILE}"
        rm -f "${SAGA_ROUTE_FILE}.orig-backup"
    fi
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
    # start_jar <registry-name> <jar> <health-url> [env assignments...]
    local name="$1" jar="$2" health="$3"
    shift 3
    echo "starting ${name}: $* java -jar ${jar}"
    env "$@" nohup java -jar "${jar}" > "${LOG_DIR}/${name}.log" 2>&1 &
    PIDS["${name}"]=$!
    wait_for "${health}" "${name}"
}

start_jar_props() {
    # start_jar_props <registry-name> <java -D props...> -- <jar> <health-url>
    local name="$1"; shift
    local props=()
    while [ "$1" != "--" ]; do props+=("$1"); shift; done
    shift
    local jar="$1" health="$2"
    echo "starting ${name}: java ${props[*]} -jar ${jar}"
    nohup java "${props[@]}" -jar "${jar}" > "${LOG_DIR}/${name}.log" 2>&1 &
    PIDS["${name}"]=$!
    wait_for "${health}" "${name}"
}

stop_named() {
    # Same graceful-shutdown discipline demo-payment-cutover.sh's stop_named
    # established: SIGTERM, then poll for exit (up to 10s) before SIGKILL,
    # so a Kafka-consuming service (shipping-service) gets a chance to send
    # a LeaveGroupRequest and not stall the NEXT consumer's rebalance.
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

# Runs the full equivalence suite through newman's Node API with the
# `shippingSagaEnabled` COLLECTION variable patched IN MEMORY (see the
# header comment above for why --env-var cannot do this). Never writes the
# collection file. Exit code: 0 if newman reported zero failures, 1
# otherwise.
run_shipping_suite() {
    local base_url="$1" saga_enabled="$2" folder="${3:-}"
    node "${SCRIPT_DIR}/lib/run-shipping-newman.js" "${PROJECT_ROOT}" "${base_url}" "${saga_enabled}" "${folder}"
}

section "0. Compose stack (Postgres + Kafka)"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the topology (cutover state)"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
start_jar review "${REVIEW_JAR}" "http://localhost:8081/api/reviews"
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1"
# The monolith's application.yml default datasource password doesn't match
# the compose-stack's actual POSTGRES_PASSWORD (pre-existing operational
# note, documented in examples/05-payment-service/CUTOVER.md) — overridden
# here every time, not a shipping-specific flag.
start_jar monolith-orchestrated "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only SHIPPING_MODE=orchestrated
start_jar_props proxy-shipping-on -Dstrangler.shipping.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"

section "2. CUTOVER run — both flags flipped, full suite through the proxy (shippingSagaEnabled=true)"
if run_shipping_suite "http://localhost:8888" "true"; then
    pass "cutover full suite green"
else
    fail "cutover full suite had failures"
fi

section "2b. Explicit before/after stock for a forced SHIP-FAIL (H3 crux)"
before="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
echo "  before: ${before}"
resp="$(curl -s -X POST http://localhost:8888/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-VISA","shippingAddress":"SHIP-FAIL"}')"
order_id="$(echo "${resp}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
after_reserve="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
echo "  immediately after POST (order ${order_id}, synchronous Reserve already committed): ${after_reserve}"
order_status=""
for i in $(seq 1 20); do
    order_status="$(curl -s "http://localhost:8888/api/orders/${order_id}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')"
    stock="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
    [ "${order_status}" = "SHIPPING_FAILED" ] && break
    sleep 0.75
done
echo "  after bounded-wait (status=${order_status}): ${stock}"
# The net-zero proof is only meaningful if a REAL decrement happened first:
# assert the middle number too (the synchronous gRPC Reserve took exactly
# the one unit this order requested) so a hypothetical never-decremented
# path (before == after_reserve == final) cannot green this check — same
# hardening demo-payment-cutover.sh applies to its decline check.
expected_after_reserve=$((before - 1))
if [ "${order_status}" = "SHIPPING_FAILED" ] \
    && [ "${after_reserve}" = "${expected_after_reserve}" ] \
    && [ "${stock}" = "${before}" ]; then
    pass "net-zero via genuine decrement+compensation: ${before} -> ${after_reserve} -> ${stock} (synchronous Reserve decremented, orchestrated compensation restored)"
elif [ "${after_reserve}" != "${expected_after_reserve}" ]; then
    fail "no genuine decrement: before=${before} after-reserve=${after_reserve} (expected ${expected_after_reserve}) -- the synchronous Reserve did not decrement, so a net-zero final is meaningless"
else
    fail "net-zero NOT achieved: before=${before} after-reserve=${after_reserve} final=${stock} status=${order_status}"
fi

section "2c. Proxy-reaches-:8088 evidence (not a monolith fall-through)"
via_proxy="$(curl -s "http://localhost:8888/api/shipments?orderId=${order_id}")"
via_service="$(curl -s "http://localhost:8088/api/shipments?orderId=${order_id}")"
via_monolith="$(curl -s "http://localhost:8080/api/shipments?orderId=${order_id}")"
echo "  via proxy   : ${via_proxy}"
echo "  via service : ${via_service}"
echo "  via monolith: ${via_monolith}   (expected empty — proves no fall-through)"
if [ "${via_proxy}" = "${via_service}" ] && [ "${via_proxy}" != "${via_monolith}" ]; then
    pass "proxy response is byte-identical to the shipping service's direct response, and differs from the monolith's — genuine routing to :8088 confirmed"
else
    fail "proxy response did not match the shipping service's direct response, or matched the monolith's — possible silent fall-through"
fi

section "3. REVERSIBILITY — both flags flipped back"
stop_named monolith-orchestrated
stop_named proxy-shipping-on
start_jar monolith-inprocess "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only
start_jar proxy-shipping-off "${PROXY_JAR}" "http://localhost:8888/api/orders"
if run_shipping_suite "http://localhost:8888" "false"; then
    pass "reversibility full suite green (Scenario 4 pending again, as expected with shippingSagaEnabled=false)"
else
    fail "reversibility full suite had failures"
fi

section "4. NEGATIVE CHECK a (DRQ-065) — disable the Camel Saga compensation wiring, Scenario 4 MUST go RED"
stop_named monolith-inprocess
stop_named proxy-shipping-off
start_jar monolith-orchestrated2 "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only SHIPPING_MODE=orchestrated
start_jar_props proxy-shipping-on2 -Dstrangler.shipping.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"

echo "backing up ${SAGA_ROUTE_FILE} (plain file copy, not git) before the deliberate break..."
cp "${SAGA_ROUTE_FILE}" "${SAGA_ROUTE_FILE}.orig-backup"
sed -i 's#^\( *\)\.compensation("direct:ship-compensate")#\1// NEGATIVE-CHECK-DISABLED (restored at end of this script): .compensation("direct:ship-compensate")#' "${SAGA_ROUTE_FILE}"
if ! grep -q 'NEGATIVE-CHECK-DISABLED' "${SAGA_ROUTE_FILE}"; then
    fail "sed did not find the .compensation(...) line to disable — aborting negative check a"
else
    echo "rebuilding shipping-service with compensation disabled..."
    (cd "${PROJECT_ROOT}/examples/06-shipping-service" && ./mvnw -q -DskipTests package)
    stop_named shipping
    start_jar shipping-broken "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1"

    echo "forcing a SHIP-FAIL order and running Scenario 4 alone, expecting RED..."
    if run_shipping_suite "http://localhost:8888" "true" "Scenario 4 — Shipping-Failure (orchestrated compensation, shipping-plan S2, DRQ-059/060/062/065 — H2/H3)"; then
        fail "expected newman to FAIL (RED) with compensation disabled, but it passed — Scenario 4 is not genuinely exercising the orchestrated compensation!"
    else
        pass "RED as expected — Scenario 4 correctly failed with the saga's compensation wiring disabled"
    fi

    echo "restoring ${SAGA_ROUTE_FILE} byte-for-byte (plain file copy, not git)..."
    cp "${SAGA_ROUTE_FILE}.orig-backup" "${SAGA_ROUTE_FILE}"
    rm -f "${SAGA_ROUTE_FILE}.orig-backup"
    echo "git status (read-only — proving the revert is byte-for-byte, NOT used to perform the revert):"
    git -C "${PROJECT_ROOT}" status --porcelain -- "${SAGA_ROUTE_FILE}"
    if [ -z "$(git -C "${PROJECT_ROOT}" status --porcelain -- "${SAGA_ROUTE_FILE}")" ]; then
        pass "working tree clean for ${SAGA_ROUTE_FILE} — byte-for-byte revert confirmed"
    else
        fail "working tree NOT clean for ${SAGA_ROUTE_FILE} after revert!"
    fi

    echo "rebuilding shipping-service with compensation restored..."
    (cd "${PROJECT_ROOT}/examples/06-shipping-service" && ./mvnw -q -DskipTests package)
    stop_named shipping-broken
    start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1"

    echo "re-running Scenario 4 alone, expecting GREEN..."
    if run_shipping_suite "http://localhost:8888" "true" "Scenario 4 — Shipping-Failure (orchestrated compensation, shipping-plan S2, DRQ-059/060/062/065 — H2/H3)"; then
        pass "GREEN again — the orchestrated compensation is restored"
    else
        fail "expected newman to PASS after restoring compensation, but it still failed"
    fi
fi

section "5. NEGATIVE CHECK b (DRQ-065) — stop the shipping consumer, Scenario 1 bounded-wait MUST go RED"
stop_named shipping
echo "shipping-service stopped — re-running Scenario 1 folder, expecting RED..."
if npx newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Scenario 1 — Happy-Path Checkout" \
    --reporters cli; then
    fail "expected newman to FAIL (RED) with the shipping service down, but it passed — the bounded-wait is not genuinely exercising the saga!"
else
    pass "RED as expected — the bounded-wait correctly failed with the shipping consumer down (order stuck AWAITING_SHIPMENT)"
fi

echo "restarting shipping-service..."
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1"
# Give the backlogged order a moment to drain through the saga before
# placing a FRESH order for the re-verification (same discipline
# demo-payment-cutover.sh uses after its own consumer restart).
sleep 3
echo "re-running Scenario 1 folder on a fresh order, expecting GREEN..."
if npx newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Scenario 1 — Happy-Path Checkout" \
    --reporters cli; then
    pass "GREEN again — the saga recovered once the shipping service came back"
else
    fail "expected newman to PASS after restarting the shipping service, but it still failed"
fi

section "Demo complete"
echo "See examples/06-shipping-service/CUTOVER.md for the full narrated evidence trail."
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: all proofs behaved as expected."
else
    echo "RESULT: one or more proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "=================================================================="
exit "${OVERALL_RESULT}"
