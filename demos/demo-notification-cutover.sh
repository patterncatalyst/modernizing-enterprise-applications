#!/usr/bin/env bash
# demos/demo-notification-cutover.sh — demonstrate the Notification context
# (ch.17, Extraction 2) standing on its own behind the finished edge router.
#
# WHAT THIS IS, ON `main`: the proxy on `main` is a FLAGLESS REST edge router
# (order-plan.md DRQ-070) — the strangler.*.enabled cutover flags are retired and
# /api/notifications routes unconditionally to the extracted notification service.
# Notification is the async context: a checkout produces an `order.placed` event,
# the notification service consumes it and records a confirmation notification.
# Proving that end to end needs the whole checkout chain (order→payment→shipping,
# with inventory reserved over gRPC), so this script brings that chain up, then
# proves the Notification Context Contract through the edge router (:8888) and the
# consumer-down negative check that makes it non-vacuous.
#
# WHAT THIS IS NOT, ON `main`: the flag-flip CUTOVER + reversibility window ch.17
# narrates (strangler.notification.enabled true→false) CANNOT be reconstructed
# against the flagless edge router. To run that, check out the matching snapshot
# where the proxy still carried its cutover flags:
#     git checkout stage/02-notification-extracted
# and read ch.17's "cutover" and "reversibility" sections alongside it.
#
# Two environmental findings shared with demo-order-cutover.sh / demo-final-
# topology.sh are handled the same way (see those scripts' headers): the
# order-service Kafka consumer group replays this repo's accumulated history once
# before it is caught up (this script waits for lag=0), and the order-service's
# fresh order-id sequence can collide with historical payment/shipping idempotency
# rows (this script burns it past the live high-water mark first). shipping-service
# is pointed at the order service (ORDER_SERVICE_BASE_URL) since the order service
# is the only thing serving /api/orders.
#
# Prerequisites: the podman stack (mea-postgres, mea-kafka) must be reachable —
# this script runs scripts/stack-up.sh if it isn't already up, but will NOT tear
# it down. Every app process this script starts is stopped on exit; the podman
# stack is always left running.
#
# Usage: demos/demo-notification-cutover.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

INVENTORY_JAR="examples/04-inventory-service/target/quarkus-app/quarkus-run.jar"
PAYMENT_JAR="examples/05-payment-service/target/quarkus-app/quarkus-run.jar"
NOTIFICATION_JAR="examples/03-notification-service/target/quarkus-app/quarkus-run.jar"
SHIPPING_JAR="examples/06-shipping-service/target/quarkus-app/quarkus-run.jar"
ORDER_JAR="examples/07-order-service/target/quarkus-app/quarkus-run.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"

LOG_DIR="$(mktemp -d /tmp/demo-notification-cutover.XXXXXX)"
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
    # Graceful SIGTERM so a Kafka-consuming service sends a LeaveGroupRequest
    # (a SIGKILL stalls the next consumer's rebalance for session.timeout.ms).
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

psql_c() {
    podman exec mea-postgres psql -U monolith -d monolith -t -A -c "$1"
}

run_order_suite() {
    # run_order_suite <base-url> <gates-enabled> [folder] -> 0 iff 0 failures
    local base_url="$1" gates_enabled="$2" folder="${3:-}"
    node "${SCRIPT_DIR}/lib/run-order-newman.js" "${PROJECT_ROOT}" "${base_url}" "${gates_enabled}" "${folder}"
}

wait_for_order_consumer_caught_up() {
    local attempts=40
    for ((i = 1; i <= attempts; i++)); do
        local lag_sum
        lag_sum="$(podman exec mea-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server localhost:9092 --describe --group order-service 2>/dev/null \
            | awk '$6 ~ /^[0-9]+$/ {sum+=$6} END {print sum+0}')"
        if [ "${lag_sum}" = "0" ]; then
            pass "order-service consumer group caught up (lag=0) after ${i} check(s)"
            return 0
        fi
        sleep 1
    done
    fail "order-service consumer group never reached lag=0 within ${attempts}s"
    return 1
}

burn_in_past_historical_max() {
    local target_url="$1" margin="${2:-5}"
    local hist_max
    hist_max="$(psql_c "
        SELECT GREATEST(
            (SELECT COALESCE(max(order_id) FILTER (WHERE order_id < 100000), 0) FROM payment.payments),
            (SELECT COALESCE(max(order_id) FILTER (WHERE order_id < 100000), 0) FROM shipping.shipments),
            (SELECT COALESCE(max(id), 0) FROM orders)
        );")"
    hist_max="$(echo "${hist_max}" | tr -d '[:space:]')"
    local target=$((hist_max + margin))
    echo "  historical order-id high-water mark: ${hist_max} (burning ${target_url} forward past ${target})"
    local current=0
    for ((i = 0; i < 1000; i++)); do
        resp="$(curl -s -X POST "${target_url}/api/orders" -H 'Content-Type: application/json' \
            -d '{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-VISA","shippingAddress":"BURN-IN"}')"
        current="$(echo "${resp}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",0))' 2>/dev/null || echo 0)"
        [ -n "${current}" ] && [ "${current}" -ge "${target}" ] && break
    done
    pass "order-id sequence now at ${current} (cleared ${target})"
}

section "0. Podman stack (Postgres + Kafka)"
if podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the checkout chain + the notification service + the flagless edge router"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1" \
    ORDER_SERVICE_BASE_URL=http://localhost:8087
start_jar order "${ORDER_JAR}" "http://localhost:8087/api/orders"
start_jar edge-router "${PROXY_JAR}" "http://localhost:8888/api/orders"

echo "forward-filling order-service's OWN customers table with customer id=1 (idempotent plain INSERT, not a migration):"
psql_c "INSERT INTO order_service.customers (id, name, email, created_at) VALUES (1, 'Ada Lovelace', 'ada@example.com', now()) ON CONFLICT (id) DO NOTHING; SELECT setval(pg_get_serial_sequence('order_service.customers','id'), (SELECT MAX(id) FROM order_service.customers));" > /dev/null

echo "waiting for the order-service Kafka consumer group to drain accumulated history..."
wait_for_order_consumer_caught_up

echo "clearing the historical order-id collision zone before any timed assertion:"
burn_in_past_historical_max "http://localhost:8087" 5
psql_c "UPDATE inventory.inventory_items SET quantity_on_hand = 500 WHERE sku = 'SKU-WIDGET-001';" > /dev/null
pass "stock topped up to 500 after burn-in (plain data UPDATE, no Flyway migration touched)"

section "2. CONTRACT — the Notification Context Contract folder through the edge router (:8888)"
echo "(a checkout reaches CONFIRMED, then its confirmation notification becomes observable via /api/notifications)"
if run_order_suite "http://localhost:8888" "true" "Notification Context Contract"; then
    pass "Notification Context Contract GREEN — the confirmation notification is produced and observable through the edge router"
else
    fail "Notification Context Contract had failures through the edge router"
fi

section "3. NEGATIVE CHECK — stop the notification service, the same folder MUST go RED"
stop_named notification
echo "notification service stopped — re-running the Notification Context Contract folder, expecting RED..."
if run_order_suite "http://localhost:8888" "true" "Notification Context Contract"; then
    fail "expected RED with the notification service down, but the folder passed — the confirmation-notification step is not genuinely exercising the consumer"
else
    pass "RED as expected — with the notification consumer down, no confirmation notification becomes observable"
fi

echo "restarting the notification service..."
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
# A short margin for the notification consumer group to rejoin and drain the
# order.placed it missed while down, before the folder's bounded-wait times it.
sleep 3
echo "re-running the Notification Context Contract folder, expecting GREEN..."
if run_order_suite "http://localhost:8888" "true" "Notification Context Contract"; then
    pass "GREEN again — the notification context recovered once the consumer came back"
else
    fail "expected GREEN after restarting the notification service, but the folder still failed"
fi

section "Demo complete"
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: the notification context serves its contract through the edge router, and the route is non-vacuous (RED when the consumer is down)."
else
    echo "RESULT: one or more proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "For the flag-flip cutover + reversibility window (ch.17), run from stage/02-notification-extracted."
echo "=================================================================="
exit "${OVERALL_RESULT}"
