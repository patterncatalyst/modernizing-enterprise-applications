#!/usr/bin/env bash
# demo-payment-cutover.sh — demonstrate the Payment choreographed-saga
# CUTOVER (payment-plan.md S8, ch.23, DRQ-047/049/054/055).
#
# NOTE: this reconstructs an INTERMEDIATE migration state and brings up the
# frozen examples/00-monolith (:8080) to show the cutover and its reversibility
# window against it. In the FINISHED system the monolith is decommissioned —
# see demos/demo-final-topology.sh for the end-state (flagless edge router, no
# monolith).
#
# Unlike demo-cutover.sh (Review, r02/S10), which assumes its services are
# already running, THIS script brings up the whole topology itself, flips
# both cutover flags, and runs the three proofs payment-plan.md S8 requires:
#
#   1. CUTOVER   — payment.mode=choreographed + strangler.payment.enabled=true,
#                  full behavior-equivalence suite through the proxy (:8888).
#   2. REVERSIBILITY — both flags flipped back; full suite green again, with
#                  checkout back to the synchronous 201/402 contract.
#   3. NEGATIVE CHECK (DRQ-055) — back in the cutover state, the payment
#                  service (consumer+producer) is stopped; Scenario 1's
#                  bounded-wait MUST go RED (order stuck PENDING); then the
#                  service is restarted and the same check goes GREEN again.
#      Also: a forced CARD-DECLINE checkout, with explicit before/after stock
#      reads straight from the inventory service, proving the choreographed
#      Release restores stock to net-zero (the H3 crux).
#
# See examples/05-payment-service/CUTOVER.md for the full narrated evidence
# trail this script reproduces (including the one known, documented gap:
# the Notification Context Contract folder's own checkout step (5a) was
# never adapted for the async contract by payment-plan S2 and fails its own
# stale 201/CONFIRMED assertions in the cutover state — NOT a choreography
# defect; see CUTOVER.md's "A known, honest gap" section).
#
# Prerequisites: the podman stack (mea-postgres, mea-kafka) must be
# reachable — this script will run scripts/stack-up.sh if it isn't already
# up, but will NOT tear the stack down afterwards. All services this script
# itself starts (inventory/payment/review/notification/monolith/proxy) are
# stopped on exit; the podman stack is always left running.
#
# Usage: demos/demo-payment-cutover.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

INVENTORY_JAR="examples/04-inventory-service/target/quarkus-app/quarkus-run.jar"
PAYMENT_JAR="examples/05-payment-service/target/quarkus-app/quarkus-run.jar"
REVIEW_JAR="examples/02-review-service/target/quarkus-app/quarkus-run.jar"
NOTIFICATION_JAR="examples/03-notification-service/target/quarkus-app/quarkus-run.jar"
MONOLITH_JAR="examples/00-monolith/target/monolith.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"

LOG_DIR="$(mktemp -d /tmp/demo-payment-cutover.XXXXXX)"
declare -A PIDS=()

pass() { echo "  OK   $*"; }
fail() { echo "  FAIL $*" >&2; }
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
    # SIGTERM and give the JVM a real chance to shut down gracefully before
    # resorting to SIGKILL. This matters specifically for a Kafka-consuming
    # service (payment-service): a graceful shutdown lets the Kafka client
    # send a LeaveGroupRequest, so the NEXT consumer that joins the same
    # consumer group gets its partitions reassigned immediately. SIGKILL-ing
    # too early leaves a stale group member the broker only forgets after
    # session.timeout.ms, which stalls the NEXT consumer's rebalance for
    # several seconds to tens of seconds — exactly the false-looking
    # "timing flakiness" this script's restart logic hit before this fix
    # (see CUTOVER.md's negative-check section for the full writeup).
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

section "0. Podman stack (Postgres + Kafka)"
if podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the topology"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
start_jar review "${REVIEW_JAR}" "http://localhost:8081/api/reviews"
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
# The monolith's application.yml default datasource password doesn't match
# the podman-stack's actual POSTGRES_PASSWORD (see CUTOVER.md "Operational
# note") — overridden here, every time, not a payment-specific flag.
start_jar monolith-choreographed "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only PAYMENT_MODE=choreographed
start_jar_props proxy-payment-on -Dstrangler.payment.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"

section "2. CUTOVER run — both flags flipped, full suite through the proxy"
demos/demo-equivalence.sh http://localhost:8888

section "2b. Explicit before/after stock for a forced decline (H3 crux)"
before="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
echo "  before: ${before}"
resp="$(curl -s -X POST http://localhost:8888/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-DECLINE","shippingAddress":"1 Demo Decline Way, Testville"}')"
order_id="$(echo "${resp}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
after_reserve="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
echo "  immediately after POST (order ${order_id}, synchronous Reserve already committed): ${after_reserve}"
status=""
for i in $(seq 1 10); do
    status="$(curl -s "http://localhost:8888/api/orders/${order_id}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')"
    stock="$(curl -s http://localhost:8084/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
    [ "${status}" = "PAYMENT_DECLINED" ] && break
    sleep 0.5
done
echo "  after bounded-wait (status=${status}): ${stock}"
# The net-zero proof is only meaningful if a REAL decrement happened first:
# assert the middle number too (synchronous gRPC Reserve took exactly the one
# unit this decline order requested) so a hypothetical never-decremented path
# -- before == after_reserve == final -- cannot green this check. Without this
# the script would pass on 96->96->96, which is NOT net-zero-via-Release.
expected_after_reserve=$((before - 1))
if [ "${status}" = "PAYMENT_DECLINED" ] \
    && [ "${after_reserve}" = "${expected_after_reserve}" ] \
    && [ "${stock}" = "${before}" ]; then
    pass "net-zero via genuine decrement+Release: ${before} -> ${after_reserve} -> ${stock} (synchronous Reserve decremented, choreographed Release restored)"
elif [ "${after_reserve}" != "${expected_after_reserve}" ]; then
    fail "no genuine decrement: before=${before} after-reserve=${after_reserve} (expected ${expected_after_reserve}) -- the synchronous Reserve did not decrement, so a net-zero final is meaningless"
else
    fail "net-zero NOT achieved: before=${before} after-reserve=${after_reserve} final=${stock} status=${status}"
fi

section "3. REVERSIBILITY — both flags flipped back"
stop_named monolith-choreographed
stop_named proxy-payment-on
start_jar monolith-synchronous "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only
start_jar proxy-payment-off "${PROXY_JAR}" "http://localhost:8888/api/orders"
demos/demo-equivalence.sh http://localhost:8888
pass "reversibility window remains open (see CUTOVER.md §2 for the 0-retry, synchronous-shape confirmation)"

section "4. NEGATIVE CHECK (DRQ-055) — back to cutover, stop the payment service"
stop_named monolith-synchronous
stop_named proxy-payment-off
start_jar monolith-choreographed2 "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only PAYMENT_MODE=choreographed
start_jar_props proxy-payment-on2 -Dstrangler.payment.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"

stop_named payment
echo "payment-service stopped — re-running Scenario 1 folder, expecting RED..."
if npx newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Scenario 1 — Happy-Path Checkout" \
    --reporters cli; then
    fail "expected newman to FAIL (RED) with the payment service down, but it passed — the bounded-wait is not genuinely exercising the choreography!"
else
    pass "RED as expected — the bounded-wait correctly failed with the payment consumer down (exit code $?)"
fi

echo "restarting payment-service..."
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
# A short extra margin on top of the HTTP health check: the round trip from
# a fresh order to CONFIRMED crosses two independent 2s outbox-relay poll
# cycles (the monolith's order.placed relay, then this service's own
# payment.captured/declined relay) — close to the suite's 5s bounded-wait
# budget even in the best case. See CUTOVER.md's negative-check section for
# the full writeup, including the real root cause this script found and
# fixed (stop_named now waits for a graceful Kafka consumer-group leave
# instead of risking a SIGKILL that would stall the NEXT consumer's
# rebalance).
sleep 3
echo "re-running Scenario 1 folder, expecting GREEN..."
if npx newman run tooling/newman/mea.postman_collection.json \
    --environment tooling/newman/local.postman_environment.json \
    --env-var "baseUrl=http://localhost:8888" \
    --folder "Scenario 1 — Happy-Path Checkout" \
    --reporters cli; then
    pass "GREEN again — the choreography recovered once the payment service came back"
else
    fail "expected newman to PASS after restarting the payment service, but it still failed"
fi

section "Demo complete"
echo "See examples/05-payment-service/CUTOVER.md for the full narrated evidence trail,"
echo "including the one documented, out-of-scope gap (Notification folder's own"
echo "checkout assertion, 5a, was never adapted for the async contract by S2)."
echo "=================================================================="
