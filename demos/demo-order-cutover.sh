#!/usr/bin/env bash
# demos/demo-order-cutover.sh — demonstrate the Order/GraphQL-gateway CUTOVER
# (order-plan.md S9, ch.26, DRQ-071/072, HARD PARTS H3/H4). This is the LAST
# flag flip and the last reversible state before S10's irreversible monolith
# decommission.
#
# NOTE: this reconstructs an INTERMEDIATE migration state against the
# PRE-DECOMMISSION proxy that still honours the strangler.*.enabled flags. On
# `main` the proxy is a flagless edge router (DRQ-070), so the flag flips below
# are no-ops there and the reversibility section cannot route back to the
# monolith — run this from the matching snapshot:
# `git checkout stage/06-order-extracted`. For the FINISHED end-state (flagless
# edge router, no monolith), see demos/demo-final-topology.sh.
#
# Mirrors demos/demo-shipping-cutover.sh's shape, extended for the two
# load-bearing differences this, the LAST extraction, introduces:
#
#   1. CUTOVER        — strangler.order.enabled=true (proxy): /api/orders
#                        (checkout command AND reads) served by the order
#                        service; order.placed now PRODUCED by the order
#                        service's own outbox. Full suite run through the
#                        proxy (:8888) with BOTH collection gate variables
#                        (shippingSagaEnabled, graphqlGatewayEnabled) patched
#                        true via demos/lib/run-order-newman.js, so Scenario 4
#                        AND the GraphQL Gateway Contract folder both
#                        genuinely execute. The GraphQL folder hits the
#                        gateway (:8090) directly — its own front door,
#                        independent of the proxy flag (DRQ-069).
#   2. REVERSIBILITY   — the order flag flipped back (default false):
#                        /api/orders served by the monolith again, full suite
#                        green (the LAST time this is possible, DRQ-072).
#   3. THREE NEGATIVE CHECKS (DRQ-071/072, H2/H3 non-vacuity) — each: a local,
#                        deliberate, byte-for-byte-reverted source edit in
#                        examples/07-order-service, rebuilt, shown RED, then
#                        reverted (verified with `git status`, never used to
#                        perform the revert itself), rebuilt, shown GREEN:
#                          a. the order service's shipment-dispatched saga
#                             reaction is made a no-op -> Scenario 1 bounded-
#                             wait RED (order stuck AWAITING_SHIPMENT).
#                          b. the read-model projection on that same reaction
#                             is skipped (write model still advances) ->
#                             Scenario 1 bounded-wait RED (the CQRS
#                             non-vacuity check, H2) -- GET reads stay stale
#                             even though the aggregate reached CONFIRMED.
#                          c. the compensating Release in onShipmentFailed is
#                             skipped -> Scenario 4 RED (non-net-zero stock).
#
# TWO GENUINE, PRE-EXISTING ENVIRONMENTAL FINDINGS this script works around
# (documented in full in examples/07-order-service/CUTOVER.md):
#
#   (i) order-service's `order-service` Kafka consumer group is BRAND NEW
#       against this repo's long-lived, heavily-reused compose Kafka broker.
#       Every chapter since ch.23 has produced payment.captured/declined and
#       shipment.dispatched/failed traffic on the SAME topics. A fresh
#       consumer group with auto.offset.reset=earliest must replay that
#       entire history once before it is "caught up" — this script waits for
#       consumer lag to reach zero after every order-service (re)start before
#       timing a scenario, exactly the same discipline
#       demo-shipping-cutover.sh applies (there: a 3s drain after restarting
#       the shipping consumer; here: a longer, lag-polled wait, because the
#       backlog is much bigger).
#   (ii) order-service's own order-id sequence starts fresh at 1 (DRQ-073:
#       "store starts EMPTY and is forward-filled only"), but payment-service
#       and shipping-service's idempotency-by-order-id guards are keyed on
#       the SAME raw order_id values the monolith's shared `orders` sequence
#       has been minting since ch.17. If order-service's sequence lands on an
#       order_id that already has a historical payment/shipment row, that
#       service's idempotency guard silently SKIPS emitting a new outcome
#       event for it, and the order is stranded (observed firsthand: the
#       very first order-service checkout, order id=1, collided with an
#       ancient monolith-era order_id=1 and never left PENDING). This script
#       burns order-service's sequence past the current historical
#       high-water mark (queried live, not hardcoded) with a safety margin
#       before placing any order whose outcome is actually asserted. The
#       SAME collision can recur in the other direction once the order flag
#       flips back to the monolith (its shared sequence can walk back into a
#       range order-service already used this session) — the reversibility
#       section burns the monolith's own sequence forward the same way
#       before placing its real checkout.
#
# A THIRD finding this script wires around directly: shipping-service's own
# `order.service.base-url` (used by its "enrich" step to read an order's
# shippingAddress and detect the SHIP-FAIL sentinel, ch.24 S5) defaults to
# the monolith (:8080). Once the order flag cuts /api/orders over to the
# order service, shipping-service must be told, at runtime, to read orders
# from there instead (ORDER_SERVICE_BASE_URL=http://localhost:8087) — or
# every SHIP-FAIL checkout silently falls back to a stub address and
# dispatches normally instead of failing. This is exactly the kind of
# cross-service wiring gap H3/H4 equivalence-proving exists to catch.
#
# Prerequisites: the compose stack (mea-postgres, mea-kafka) must be
# reachable — this script runs scripts/stack-up.sh if it isn't already up,
# but will NOT tear the stack down afterwards. All services this script
# itself starts (inventory/payment/review/notification/shipping/order/
# graphql-gateway/monolith/proxy) are stopped on exit; the compose stack is
# always left running.
#
# Usage: demos/demo-order-cutover.sh

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
MONOLITH_JAR="examples/00-monolith/target/monolith.jar"
PROXY_JAR="examples/01-strangler-proxy/target/quarkus-app/quarkus-run.jar"
SAGA_LISTENER_FILE="examples/07-order-service/src/main/java/dev/patterncatalyst/order/OrderSagaListener.java"

LOG_DIR="$(mktemp -d /tmp/demo-order-cutover.XXXXXX)"
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
    # Safety net: if a negative-check backup was left behind by a script
    # failure partway through, restore it and never leave the working tree
    # dirty.
    if [ -f "${SAGA_LISTENER_FILE}.orig-backup" ]; then
        echo "  restoring ${SAGA_LISTENER_FILE} from backup left by an interrupted run"
        cp "${SAGA_LISTENER_FILE}.orig-backup" "${SAGA_LISTENER_FILE}"
        rm -f "${SAGA_LISTENER_FILE}.orig-backup"
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
    docker exec mea-postgres psql -U monolith -d monolith -t -A -c "$1"
}

# Runs the full equivalence/contract suite through newman's Node API with
# BOTH `shippingSagaEnabled` AND `graphqlGatewayEnabled` COLLECTION
# variables patched IN MEMORY (see demos/lib/run-order-newman.js's header
# comment for why --env-var cannot do this). Never writes the collection
# file. Exit code: 0 if newman reported zero failures, 1 otherwise.
run_order_suite() {
    local base_url="$1" gates_enabled="$2" folder="${3:-}"
    node "${SCRIPT_DIR}/lib/run-order-newman.js" "${PROJECT_ROOT}" "${base_url}" "${gates_enabled}" "${folder}"
}

# Waits for the order-service's `order-service` Kafka consumer group to
# report zero lag on all four saga topics — see this script's header
# comment, finding (i). Without this, a timed scenario run immediately after
# an order-service (re)start can spuriously fail while the consumer group is
# still draining historical backlog or completing a post-restart rebalance.
wait_for_order_consumer_caught_up() {
    local attempts=40
    for ((i = 1; i <= attempts; i++)); do
        local lag_sum
        lag_sum="$(docker exec mea-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
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

# Burns in throwaway checkouts directly against a service's own REST API
# until its order-id sequence clears the CURRENT historical high-water mark
# across payment/shipping/the monolith's own orders table (queried live —
# see this script's header comment, finding (ii)). This is pure application
# traffic (no DB admin mutation) and is idempotent to re-run: if the target
# is already clear, it burns in zero orders.
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

section "0. Compose stack (Postgres + Kafka)"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-postgres$' \
    && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$'; then
    pass "mea-postgres and mea-kafka already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi

section "1. Bring up the full topology (cutover state, strangler.order.enabled=true)"
start_jar inventory "${INVENTORY_JAR}" "http://localhost:8084/api/inventory"
start_jar payment "${PAYMENT_JAR}" "http://localhost:8085/api/payments?orderId=1"
start_jar review "${REVIEW_JAR}" "http://localhost:8081/api/reviews"
start_jar notification "${NOTIFICATION_JAR}" "http://localhost:8083/api/notifications?customerId=1"
# ORDER_SERVICE_BASE_URL -- finding (iii) in this script's header comment:
# shipping-service's "enrich" step must read orders from the order service,
# not its own default (the monolith), once the order flag is on.
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1" \
    ORDER_SERVICE_BASE_URL=http://localhost:8087
start_jar order "${ORDER_JAR}" "http://localhost:8087/api/orders"
start_jar gateway "${GATEWAY_JAR}" "http://localhost:8090/q/health"
start_jar monolith "${MONOLITH_JAR}" "http://localhost:8080/api/orders" \
    SPRING_DATASOURCE_PASSWORD=monolith_dev_only
start_jar_props proxy-order-on -Dstrangler.order.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"

echo "forward-filling order-service's OWN customers table (DRQ-073: store starts EMPTY, forward-filled only) with customer id=1 to match the suite's checkout fixtures -- idempotent plain INSERT, not a migration:"
psql_c "INSERT INTO order_service.customers (id, name, email, created_at) VALUES (1, 'Ada Lovelace', 'ada@example.com', now()) ON CONFLICT (id) DO NOTHING; SELECT setval(pg_get_serial_sequence('order_service.customers','id'), (SELECT MAX(id) FROM order_service.customers));" > /dev/null

echo "waiting for the order-service's brand-new Kafka consumer group to drain this repo's accumulated history (finding i)..."
wait_for_order_consumer_caught_up

echo "clearing the historical order-id collision zone (finding ii) before any timed assertion:"
burn_in_past_historical_max "http://localhost:8087" 5
psql_c "UPDATE inventory.inventory_items SET quantity_on_hand = 500 WHERE sku = 'SKU-WIDGET-001';" > /dev/null
pass "stock topped up to 500 after burn-in (plain data UPDATE, no Flyway migration touched)"

section "2. CUTOVER run — strangler.order.enabled=true, both gate vars true, full suite through the proxy"
if run_order_suite "http://localhost:8888" "true"; then
    pass "cutover full suite green"
else
    fail "cutover full suite had failures (the one known, pre-existing GraphQL content-type header gap is expected -- see CUTOVER.md; any OTHER failure is a real regression)"
fi

section "2b. Explicit before/after stock for a forced SHIP-FAIL and a forced DECLINE"
for case in "SHIP-FAIL:SHIPPING_FAILED:1 Equivalence Suite Way, Testville" "DECLINE:PAYMENT_DECLINED:1 Equivalence Suite Way, Testville"; do
    tag="${case%%:*}"
    rest="${case#*:}"
    expect_status="${rest%%:*}"
    addr="${rest#*:}"
    if [ "${tag}" = "SHIP-FAIL" ]; then
        body='{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-VISA","shippingAddress":"SHIP-FAIL"}'
    else
        body='{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-DECLINE","shippingAddress":"1 Equivalence Suite Way, Testville"}'
    fi
    before="$(curl -s http://localhost:8888/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
    resp="$(curl -s -X POST http://localhost:8888/api/orders -H 'Content-Type: application/json' -d "${body}")"
    order_id="$(echo "${resp}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
    after_reserve="$(curl -s http://localhost:8888/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
    status=""
    for i in $(seq 1 20); do
        status="$(curl -s "http://localhost:8888/api/orders/${order_id}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')"
        final_stock="$(curl -s http://localhost:8888/api/inventory/SKU-WIDGET-001 | python3 -c 'import json,sys;print(json.load(sys.stdin)["quantityOnHand"])')"
        [ "${status}" = "${expect_status}" ] && break
        sleep 0.75
    done
    expected_after_reserve=$((before - 1))
    echo "  ${tag} order ${order_id}: ${before} -> ${after_reserve} -> ${final_stock} (status=${status})"
    if [ "${status}" = "${expect_status}" ] && [ "${after_reserve}" = "${expected_after_reserve}" ] && [ "${final_stock}" = "${before}" ]; then
        pass "${tag} net-zero via genuine decrement+compensation"
    else
        fail "${tag} did NOT reach net-zero: before=${before} after-reserve=${after_reserve} final=${final_stock} status=${status}"
    fi
done

section "2c. Order-flag-reaches-:8087 proof (not a monolith fall-through)"
proxy_body="$(curl -s "http://localhost:8888/api/orders/${order_id}")"
service_body="$(curl -s "http://localhost:8087/api/orders/${order_id}")"
monolith_code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8080/api/orders/${order_id}")"
echo "  via proxy   : ${proxy_body}"
echo "  via service : ${service_body}"
echo "  via monolith: HTTP ${monolith_code} (expected 404 -- the monolith never saw this order)"
if [ "${proxy_body}" = "${service_body}" ] && [ "${monolith_code}" = "404" ]; then
    pass "proxy response byte-identical to the order service's direct response; monolith has no knowledge of this order"
else
    fail "proxy did not match the order service's direct response, or the monolith unexpectedly knew about this order"
fi

section "3. REVERSIBILITY — strangler.order.enabled flips back to false (the LAST time, DRQ-072)"
stop_named proxy-order-on
stop_named shipping
start_jar proxy-order-off "${PROXY_JAR}" "http://localhost:8888/api/orders"
# shipping-service reverts to ITS OWN default order-service base-url (the
# monolith) since orders are served by the monolith again under reversibility.
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1"

echo "clearing the historical order-id collision zone for the MONOLITH's own sequence (finding ii, recurring in the other direction) before the real reversibility checkout:"
burn_in_past_historical_max "http://localhost:8080" 5

resp="$(curl -s -X POST http://localhost:8888/api/orders -H 'Content-Type: application/json' \
    -d '{"customerId":1,"items":[{"sku":"SKU-WIDGET-001","quantity":1}],"paymentMethod":"CARD-VISA","shippingAddress":"1 Reversibility Way"}')"
rev_order_id="$(echo "${resp}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
rev_status=""
for i in $(seq 1 15); do
    rev_status="$(curl -s "http://localhost:8888/api/orders/${rev_order_id}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])')"
    [ "${rev_status}" = "CONFIRMED" ] && break
    sleep 1
done
order_service_sees_it="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8087/api/orders/${rev_order_id}")"
echo "  reversibility checkout order ${rev_order_id}: status=${rev_status}; order-service's own view: HTTP ${order_service_sees_it} (expected 404 -- it never saw this order)"
if [ "${rev_status}" = "CONFIRMED" ] && [ "${order_service_sees_it}" = "404" ]; then
    pass "reversibility checkout CONFIRMED via the monolith; the order service has no knowledge of it"
else
    fail "reversibility checkout did not reach CONFIRMED via the monolith as expected"
fi

if run_order_suite "http://localhost:8888" "true"; then
    pass "reversibility full suite green"
else
    fail "reversibility full suite had failures (the GraphQL GG-a correlation gap documented in CUTOVER.md is expected here too -- the gateway only ever resolves orders via the order service, so a monolith-served Scenario-1 order under reversibility is legitimately not found; any OTHER failure is a real regression)"
fi

echo "re-cutting over for the negative checks (order flag back on, shipping pointed at the order service again):"
stop_named proxy-order-off
stop_named shipping
start_jar shipping "${SHIPPING_JAR}" "http://localhost:8088/api/shipments?orderId=1" \
    ORDER_SERVICE_BASE_URL=http://localhost:8087
start_jar_props proxy-order-on2 -Dstrangler.order.enabled=true -- "${PROXY_JAR}" "http://localhost:8888/api/orders"
burn_in_past_historical_max "http://localhost:8087" 5
psql_c "UPDATE inventory.inventory_items SET quantity_on_hand = 500 WHERE sku = 'SKU-WIDGET-001';" > /dev/null

negative_check() {
    # negative_check <label> <sed-expression> <folder> <expect-status-field-in-fail-message>
    local label="$1" sed_expr="$2" folder="$3"
    section "NEGATIVE CHECK — ${label}"
    echo "backing up ${SAGA_LISTENER_FILE} (plain file copy, not git) before the deliberate break..."
    cp "${SAGA_LISTENER_FILE}" "${SAGA_LISTENER_FILE}.orig-backup"
    sed -i "${sed_expr}" "${SAGA_LISTENER_FILE}"
    if cmp -s "${SAGA_LISTENER_FILE}" "${SAGA_LISTENER_FILE}.orig-backup"; then
        fail "sed did not change ${SAGA_LISTENER_FILE} -- aborting this negative check"
        rm -f "${SAGA_LISTENER_FILE}.orig-backup"
        return
    fi
    echo "rebuilding order-service with the break applied..."
    (cd "${PROJECT_ROOT}/examples/07-order-service" && ./mvnw -q -DskipTests package)
    stop_named order
    start_jar order-broken "${ORDER_JAR}" "http://localhost:8087/api/orders"
    wait_for_order_consumer_caught_up

    echo "forcing a fresh order and running ${folder}, expecting RED..."
    if run_order_suite "http://localhost:8888" "true" "${folder}"; then
        fail "expected newman to FAIL (RED) with the break applied, but it passed -- ${label} is not genuinely exercising this reaction!"
    else
        pass "RED as expected -- ${label}"
    fi

    echo "restoring ${SAGA_LISTENER_FILE} byte-for-byte (plain file copy, not git)..."
    cp "${SAGA_LISTENER_FILE}.orig-backup" "${SAGA_LISTENER_FILE}"
    rm -f "${SAGA_LISTENER_FILE}.orig-backup"
    echo "git status (read-only -- proving the revert is byte-for-byte, NOT used to perform the revert):"
    git -C "${PROJECT_ROOT}" status --porcelain -- "${SAGA_LISTENER_FILE}"
    if [ -z "$(git -C "${PROJECT_ROOT}" status --porcelain -- "${SAGA_LISTENER_FILE}")" ]; then
        pass "working tree clean for ${SAGA_LISTENER_FILE} -- byte-for-byte revert confirmed"
    else
        fail "working tree NOT clean for ${SAGA_LISTENER_FILE} after revert!"
    fi

    echo "rebuilding order-service with the reaction restored..."
    (cd "${PROJECT_ROOT}/examples/07-order-service" && ./mvnw -q -DskipTests package)
    stop_named order-broken
    start_jar order "${ORDER_JAR}" "http://localhost:8087/api/orders"
    wait_for_order_consumer_caught_up

    echo "re-running ${folder}, expecting GREEN..."
    if run_order_suite "http://localhost:8888" "true" "${folder}"; then
        pass "GREEN again -- ${label} restored"
    else
        fail "expected newman to PASS after restoring ${label}, but it still failed"
    fi
}

negative_check \
    "(a) order saga consumer reaction disabled (onShipmentDispatched made a no-op) -> Scenario 1 stuck AWAITING_SHIPMENT" \
    '0,/public void onShipmentDispatched(ShipmentDispatched event) {/{s/public void onShipmentDispatched(ShipmentDispatched event) {/public void onShipmentDispatched(ShipmentDispatched event) {\n        if (true) { LOG.warnf("NEGATIVE-CHECK-DISABLED: no-op for orderId=%d", event.orderId()); return; }/}' \
    "Scenario 1 — Happy-Path Checkout"

negative_check \
    "(b) read-model projection disabled on onShipmentDispatched -> order_view stays stale (H2 CQRS non-vacuity)" \
    's#orderViewProjector\.project(order, null, "DISPATCHED");#// orderViewProjector.project(order, null, "DISPATCHED"); // NEGATIVE-CHECK-DISABLED#' \
    "Scenario 1 — Happy-Path Checkout"

negative_check \
    "(c) compensating Release disabled in onShipmentFailed -> Scenario 4 non-net-zero" \
    '0,/remoteInventoryClient\.release(item\.getSku(), item\.getQuantity());/{s#remoteInventoryClient\.release(item\.getSku(), item\.getQuantity());#// remoteInventoryClient.release(item.getSku(), item.getQuantity()); // NEGATIVE-CHECK-DISABLED#}' \
    "Scenario 4 — Shipping-Failure (orchestrated compensation, shipping-plan S2, DRQ-059/060/062/065 — H2/H3)"

section "Demo complete"
echo "See examples/07-order-service/CUTOVER.md for the full narrated evidence trail."
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: all proofs behaved as expected."
else
    echo "RESULT: one or more proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "=================================================================="
exit "${OVERALL_RESULT}"
