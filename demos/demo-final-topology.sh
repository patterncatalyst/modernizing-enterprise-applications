#!/usr/bin/env bash
# demos/demo-final-topology.sh — demonstrate the FINISHED, end-state topology:
# the six extracted Quarkus services + the GraphQL aggregation gateway, fronted
# by the Camel proxy that is now a FLAGLESS REST edge router, with the Spring
# monolith DECOMMISSIONED (never started, unwired from every route).
#
# This is the capstone the book culminates in, and the one state none of the
# cutover demos show: every earlier demo (demo-cutover / demo-payment-cutover /
# demo-shipping-cutover / demo-order-cutover) deliberately reconstructs an
# INTERMEDIATE migration state and brings the monolith up on :8080 to show a
# strangler cutover and its reversibility window. This one shows the opposite —
# the tree the strangler fig grew into once the original trunk was cut.
#
# What it proves, in order:
#   1. TOPOLOGY     — the seven app processes come up with NO monolith:
#                       review :8081, notification :8083, inventory :8084
#                       (gRPC :9004), payment :8085, order :8087, shipping
#                       :8088, graphql-gateway :8090 — behind the edge router
#                       :8888. The proxy is started plain: no strangler.*.enabled
#                       flags exist anymore (StranglerProxyRoute collapsed to a
#                       single content-based choice() per /api/<context>).
#   2. CONTRACT     — the full behavior/contract suite runs GREEN through the
#                       edge router (:8888) with both collection gate variables
#                       (shippingSagaEnabled, graphqlGatewayEnabled) patched true
#                       via demos/lib/run-order-newman.js, so Scenario 4 and the
#                       GraphQL Gateway Contract folder both execute. The suite
#                       that was captured against the monolith now passes with
#                       the monolith gone — the contract outlived its first
#                       implementation.
#   3. AGGREGATION  — a GraphQL order(id) query hit DIRECTLY against the gateway
#                       (:8090/graphql, its own front door) returns one
#                       OrderAggregate stitched from the order service (REST),
#                       inventory (gRPC), reviews/payments/shipments — proving
#                       the data-less aggregation gateway resolves across
#                       protocols.
#   4. UNWIRED      — the monolith is genuinely out of the topology: nothing is
#                       listening on :8080, and the edge router has NO monolith
#                       fall-through (an unknown /api path returns 404 from the
#                       router's own .otherwise(), not a proxied monolith reply).
#
# Two environmental findings shared with demo-order-cutover.sh are handled the
# same way here (see that script's header for the full writeup):
#   (i)  order-service's `order-service` Kafka consumer group replays this repo's
#        accumulated saga-topic history once before it is caught up — this script
#        waits for consumer lag to reach zero before any timed assertion.
#   (ii) order-service's fresh order-id sequence can collide with historical
#        payment/shipping idempotency rows — this script burns the sequence past
#        the live historical high-water mark before placing any asserted order.
# And the third (shipping-service's enrich step must read orders from the order
# service, not its monolith default): shipping is started with
# ORDER_SERVICE_BASE_URL=http://localhost:8087, since in the end state the order
# service is the only thing serving /api/orders.
#
# Prerequisites: the podman stack (mea-postgres, mea-kafka) must be reachable —
# this script runs scripts/stack-up.sh if it isn't already up, but will NOT tear
# the stack down afterwards. Every app process this script starts is stopped on
# exit; the podman stack is always left running.
#
# Usage: demos/demo-final-topology.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

INVENTORY_JAR="examples/04-inventory-service/target/quarkus-app/quarkus-run.jar"
PAYMENT_JAR="examples/05-payment-service/target/quarkus-app/quarkus-run.jar"
REVIEW_JAR="examples/02-review-service/target/quarkus-app/quarkus-run.jar"
NOTIFICATION_JAR="examples/03-notification-service/target/quarkus-app/quarkus-run.jar"
SHIPPING_JAR="examples/06-shipping-service/target/quarkus-app/quarkus-run.jar"
ORDER_JAR="examples/07-order-service/target/quarkus-app/quarkus-run.jar"
GATEWAY_JAR="examples/08-graphql-gateway/target/quarkus-app/quarkus-run.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"

LOG_DIR="$(mktemp -d /tmp/demo-final-topology.XXXXXX)"
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
    # start_jar <registry-name> <jar> <health-url> [env assignments...]
    local name="$1" jar="$2" health="$3"
    shift 3
    echo "starting ${name}: $* java -jar ${jar}"
    env "$@" nohup java -jar "${jar}" > "${LOG_DIR}/${name}.log" 2>&1 &
    PIDS["${name}"]=$!
    wait_for "${health}" "${name}"
}

psql_c() {
    podman exec mea-postgres psql -U monolith -d monolith -t -A -c "$1"
}

# Full contract suite through newman's Node API with BOTH gate collection
# variables patched in memory — identical to demo-order-cutover.sh. Exit 0 iff
# newman reported zero failures.
run_order_suite() {
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

json_field() {
    # json_field <json-string> <python-expr-on-obj-named-d>
    python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$1" "$2" 2>/dev/null
}

section "0. Podman stack (Postgres + Kafka)"
if podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && podman ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the END-STATE topology (six services + gateway + flagless edge router, NO monolith)"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
start_jar review "${REVIEW_JAR}" "http://localhost:8081/api/reviews"
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
# shipping-service's enrich step reads orders from the order service (the only
# thing serving /api/orders in the end state), not its monolith default.
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1" \
    ORDER_SERVICE_BASE_URL=http://localhost:8087
start_jar order "${ORDER_JAR}" "http://localhost:8087/api/orders"
start_jar gateway "${GATEWAY_JAR}" "http://localhost:8090/q/health"
# The proxy is started PLAIN — it is a flagless REST edge router now; there are
# no strangler.*.enabled properties left to set.
start_jar edge-router "${PROXY_JAR}" "http://localhost:8888/api/orders"

echo "forward-filling order-service's OWN customers table (store starts empty, forward-filled only) with customer id=1 to match the suite's fixtures -- idempotent plain INSERT, not a migration:"
psql_c "INSERT INTO order_service.customers (id, name, email, created_at) VALUES (1, 'Ada Lovelace', 'ada@example.com', now()) ON CONFLICT (id) DO NOTHING; SELECT setval(pg_get_serial_sequence('order_service.customers','id'), (SELECT MAX(id) FROM order_service.customers));" > /dev/null

echo "waiting for the order-service Kafka consumer group to drain this repo's accumulated history (finding i)..."
wait_for_order_consumer_caught_up

echo "clearing the historical order-id collision zone (finding ii) before any timed assertion:"
burn_in_past_historical_max "http://localhost:8087" 5
psql_c "UPDATE inventory.inventory_items SET quantity_on_hand = 500 WHERE sku = 'SKU-WIDGET-001';" > /dev/null
pass "stock topped up to 500 after burn-in (plain data UPDATE, no Flyway migration touched)"

section "2. CONTRACT — full suite through the edge router (:8888), both gate vars true, monolith absent"
if run_order_suite "http://localhost:8888" "true"; then
    pass "end-state contract suite GREEN through the edge router — the contract outlived the monolith it was captured from"
else
    fail "end-state contract suite had failures — any failure here is a real regression (the monolith is gone, so there is no reversibility caveat to excuse one)"
fi

section "3. AGGREGATION — a GraphQL order(id) query direct against the gateway (:8090)"
echo "placing one happy-path order through the edge router, then resolving it through the gateway..."
resp="$(curl -s -X POST http://localhost:8888/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":2}],"paymentMethod":"CARD-VISA","shippingAddress":"1 Aggregation Way, Testville"}')"
agg_order_id="$(json_field "${resp}" 'd["id"]')"
agg_status=""
for i in $(seq 1 20); do
    agg_status="$(curl -s "http://localhost:8888/api/orders/${agg_order_id}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])' 2>/dev/null)"
    [ "${agg_status}" = "CONFIRMED" ] && break
    sleep 1
done
echo "  order ${agg_order_id} reached status=${agg_status}; querying the gateway..."
gql_query='{"query":"query($id: ID!){ order(id:$id){ id status items{ sku quantity stock{ quantityOnHand } reviews{ id } } payments{ id status } shipments{ id status } } }","variables":{"id":"'"${agg_order_id}"'"}}'
gql_resp="$(curl -s -X POST http://localhost:8090/graphql -H 'Content-Type: application/json' -d "${gql_query}")"
echo "  gateway response: ${gql_resp}"
gql_id="$(json_field "${gql_resp}" 'd["data"]["order"]["id"]')"
gql_has_stock="$(json_field "${gql_resp}" 'd["data"]["order"]["items"][0]["stock"] is not None')"
gql_has_payments="$(json_field "${gql_resp}" 'len(d["data"]["order"]["payments"]) >= 1')"
gql_has_shipments="$(json_field "${gql_resp}" 'len(d["data"]["order"]["shipments"]) >= 1')"
if [ "${agg_status}" = "CONFIRMED" ] && [ "${gql_id}" = "${agg_order_id}" ] \
    && [ "${gql_has_stock}" = "True" ] && [ "${gql_has_payments}" = "True" ] && [ "${gql_has_shipments}" = "True" ]; then
    pass "gateway aggregated order ${agg_order_id} across REST (order/reviews/payments/shipments) and gRPC (inventory stock) into one OrderAggregate"
else
    fail "gateway aggregation incomplete: id=${gql_id} stock=${gql_has_stock} payments=${gql_has_payments} shipments=${gql_has_shipments} status=${agg_status}"
fi

section "4. UNWIRED — the monolith is out of the topology"
monolith_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:8080/api/orders" || true)"
echo "  direct GET http://localhost:8080/api/orders -> HTTP '${monolith_code}' (expected 000/connection-refused: nothing is listening)"
if [ "${monolith_code}" = "000" ] || [ -z "${monolith_code}" ]; then
    pass "nothing is listening on :8080 — the monolith is not running"
else
    fail "something answered on :8080 (HTTP ${monolith_code}) — a monolith process is unexpectedly up"
fi

unknown_code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "http://localhost:8888/api/this-context-does-not-exist")"
echo "  GET http://localhost:8888/api/this-context-does-not-exist -> HTTP ${unknown_code} (expected 404 from the router's own .otherwise(), not a monolith fall-through)"
if [ "${unknown_code}" = "404" ]; then
    pass "the edge router 404s unknown contexts from its own .otherwise() — there is no default-to-monolith backend"
else
    fail "unexpected HTTP ${unknown_code} for an unknown context — the router may still have a fall-through backend"
fi

section "Demo complete"
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: the finished topology stands on its own — six services + gateway behind a flagless edge router, contract green, aggregation working, monolith unwired."
else
    echo "RESULT: one or more end-state proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "=================================================================="
exit "${OVERALL_RESULT}"
