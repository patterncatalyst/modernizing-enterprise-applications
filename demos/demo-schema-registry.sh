#!/usr/bin/env bash
# demos/demo-schema-registry.sh — demonstrate Avro + Apicurio Schema Registry
# (ch.28, DRQ-038 follow-up/DRQ-076) via the ISOLATED, ADDITIVE
# examples/09-schema-registry-demo module.
#
# WHAT THIS IS: a standalone demonstrator. examples/09-schema-registry-demo
# publishes a field-for-field Avro mirror of examples/07-order-service's
# OrderPlacedEvent (dev.patterncatalyst.contracts.avro.OrderPlaced) to its OWN
# topic (order.events.avro.demo), fronted by the `apicurio` compose service
# (quay.io/apicurio/apicurio-registry:3.3.3, v3 API). This script: (1) brings
# up the stack (which now includes `apicurio`), (2) builds and starts the
# demo app, (3) POSTs a demo order and shows the consumer deserializing it
# back, (4) curls the registry API to show the auto-registered artifact +
# version, then (5) applies a BACKWARD compatibility rule to that SAME
# artifact and shows a schema evolution that adds a field WITH a default
# (v2) succeed, and one that retypes a field incompatibly (v3) rejected with
# HTTP 409 / RuleViolationException.
#
# WHAT THIS IS NOT: this does NOT touch, replace, or reroute the real
# order.placed JSON event (DRQ-038) — examples/00-monolith through
# examples/07-order-service, and every consumer of that topic
# (notification/payment), are completely untouched by this script and by the
# module it exercises. There is no flag to flip and no cutover to reverse;
# this is a teaching surface for the Avro/registry mechanics in isolation,
# not a second production event path. The live checkout flows stay JSON
# forever (DRQ-038); only this one demonstrator module uses Avro.
#
# Prerequisites: the compose stack (mea-kafka, mea-apicurio) must be
# reachable — this script runs scripts/stack-up.sh if it isn't already up,
# but will NOT tear it down. The demo app process this script starts is
# stopped on exit; the compose stack is always left running.
#
# Usage: demos/demo-schema-registry.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

MODULE_DIR="examples/09-schema-registry-demo"
APP_JAR="${MODULE_DIR}/target/quarkus-app/quarkus-run.jar"
APP_BASE_URL="http://localhost:8096"
ARTIFACT_ID="order.events.avro.demo-value"
V2_FIXTURE="${MODULE_DIR}/src/test/resources/avro/order-placed-v2-backward-compatible.avsc"
V3_FIXTURE="${MODULE_DIR}/src/test/resources/avro/order-placed-v3-incompatible.avsc"

# shellcheck disable=SC2034  # read via .env sourcing below if present
APICURIO_HOST_PORT_DEFAULT=8095

LOG_DIR="$(mktemp -d /tmp/demo-schema-registry.XXXXXX)"
APP_PID=""
OVERALL_RESULT=0

pass() { echo "  OK   $*"; }
fail() { echo "  FAIL $*" >&2; OVERALL_RESULT=1; }
section() { echo; echo "== $* =========================================================="; }

cleanup() {
    section "Cleanup — stopping the demo app (compose stack left running)"
    if [ -n "${APP_PID}" ] && kill -0 "${APP_PID}" 2>/dev/null; then
        kill "${APP_PID}" 2>/dev/null
        echo "  stopped schema-registry-demo (pid ${APP_PID})"
        # Graceful SIGTERM, then wait for the process to actually exit
        # (same discipline as demos/demo-notification-cutover.sh's
        # stop_named()): a Kafka consumer needs time to send a
        # LeaveGroupRequest on shutdown. A SIGKILL before that completes
        # leaves a zombie member in the "schema-registry-demo" consumer
        # group, which stalls the NEXT run's rebalance for up to the
        # broker's session.timeout.ms (confirmed by hand: skipping this
        # wait made a subsequent run's consumer take 20+ seconds to receive
        # its first record).
        for i in $(seq 1 10); do
            kill -0 "${APP_PID}" 2>/dev/null || break
            sleep 1
        done
        kill -9 "${APP_PID}" 2>/dev/null || true
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

# Resolve the registry's host port from .env (defaults to 8095, matching
# compose.yaml/.env.example's APICURIO_HOST_PORT), so this script tracks a
# non-default override without edits.
registry_host_port() {
    if [ -f .env ]; then
        local port
        port="$(grep -E '^APICURIO_HOST_PORT=' .env | tail -1 | cut -d= -f2)"
        [ -n "${port}" ] && { echo "${port}"; return; }
    fi
    echo "${APICURIO_HOST_PORT_DEFAULT}"
}
REGISTRY_BASE_URL="http://localhost:$(registry_host_port)/apis/registry/v3"

section "0. Compose stack (Kafka + Apicurio)"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-kafka$' \
    && docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^mea-apicurio$'; then
    pass "mea-kafka and mea-apicurio already up — leaving as-is"
else
    echo "stack not detected — running scripts/stack-up.sh"
    "${PROJECT_ROOT}/scripts/stack-up.sh"
fi
wait_for "${REGISTRY_BASE_URL}/system/info" "Apicurio Registry"

section "1. Build and start the schema-registry-demo app (:8096)"
if [ ! -f "${APP_JAR}" ]; then
    echo "jar not found — building (mvn -DskipTests package)..."
    (cd "${MODULE_DIR}" && mvn -q -DskipTests package)
fi
echo "starting schema-registry-demo: java -jar ${APP_JAR}"
nohup java -jar "${APP_JAR}" > "${LOG_DIR}/schema-registry-demo.log" 2>&1 &
APP_PID=$!
wait_for "${APP_BASE_URL}/q/health" "schema-registry-demo"

section "2. Emit a demo OrderPlaced event as Avro (POST /demo/orders)"
RESPONSE="$(curl -s -X POST "${APP_BASE_URL}/demo/orders")"
echo "  response: ${RESPONSE}"
if echo "${RESPONSE}" | grep -q '"orderId"'; then
    pass "demo order emitted"
else
    fail "POST /demo/orders did not return an OrderPlaced payload"
fi

section "3. Show the consumer deserializing the SAME event back out"
# Bounded-wait, not a fixed sleep: on a freshly (re)started JVM, the
# "schema-registry-demo" consumer group's initial partition assignment
# (group rebalance) can itself take a few seconds, independent of how fast
# the broker ack'd the producer's send above.
CONSUMED_OK=0
for i in $(seq 1 20); do
    if grep -q "schema-registry-demo: consumed OrderPlaced" "${LOG_DIR}/schema-registry-demo.log"; then
        CONSUMED_OK=1
        break
    fi
    sleep 1
done
if [ "${CONSUMED_OK}" = "1" ]; then
    pass "consumer deserialized the event — log line:"
    grep "schema-registry-demo: consumed OrderPlaced" "${LOG_DIR}/schema-registry-demo.log" | tail -1
else
    fail "no 'consumed OrderPlaced' log line found within 20s — the round trip did not complete"
fi

section "4. Apicurio — show the auto-registered artifact + version"
echo "artifact (group 'default', id '${ARTIFACT_ID}'):"
curl -s "${REGISTRY_BASE_URL}/groups/default/artifacts/${ARTIFACT_ID}" | python3 -m json.tool || \
    fail "could not fetch the auto-registered artifact from Apicurio"
echo
echo "versions:"
curl -s "${REGISTRY_BASE_URL}/groups/default/artifacts/${ARTIFACT_ID}/versions" | python3 -m json.tool || \
    fail "could not list versions for ${ARTIFACT_ID}"

section "5. Create a FRESH artifact + apply the BACKWARD compatibility rule"
# Deliberately a SEPARATE, uniquely-suffixed artifact from the live
# producer's auto-registered "${ARTIFACT_ID}" above (steps 4) — this makes
# steps 5-7 idempotent across repeated runs of this script (no
# VersionAlreadyExistsException from a prior run's leftover state), while
# still using the SAME v1 schema content the producer emits, so the teaching
# point ("this is the contract the running app actually produces") holds.
V1_FIXTURE="${MODULE_DIR}/src/main/avro/order-placed-v1.avsc"
EVOLUTION_ARTIFACT_ID="order-placed-schema-evolution-demo-$(date +%s)"
V1_CREATE_BODY="$(python3 -c '
import json, sys
schema = open(sys.argv[2]).read()
print(json.dumps({
    "artifactId": sys.argv[1],
    "artifactType": "AVRO",
    "firstVersion": {"version": "1", "content": {"content": schema, "contentType": "application/json"}},
}))
' "${EVOLUTION_ARTIFACT_ID}" "${V1_FIXTURE}")"
CREATE_STATUS="$(curl -s -o "${LOG_DIR}/create_response.json" -w '%{http_code}' -X POST \
    "${REGISTRY_BASE_URL}/groups/default/artifacts" \
    -H 'Content-Type: application/json' --data "${V1_CREATE_BODY}")"
if [ "${CREATE_STATUS}" = "200" ]; then
    pass "created ${EVOLUTION_ARTIFACT_ID} v1 (HTTP ${CREATE_STATUS})"
else
    fail "expected HTTP 200 creating ${EVOLUTION_ARTIFACT_ID}, got ${CREATE_STATUS}: $(cat "${LOG_DIR}/create_response.json")"
fi

RULE_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "${REGISTRY_BASE_URL}/groups/default/artifacts/${EVOLUTION_ARTIFACT_ID}/rules" \
    -H 'Content-Type: application/json' \
    --data '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}')"
if [ "${RULE_STATUS}" = "204" ]; then
    pass "BACKWARD compatibility rule applied (HTTP ${RULE_STATUS})"
else
    fail "expected HTTP 204 applying the COMPATIBILITY rule, got ${RULE_STATUS}"
fi

section "6. v2 — adds giftMessage WITH a default (BACKWARD-compatible) — expect ACCEPTED"
V2_BODY="$(python3 -c '
import json, sys
schema = open(sys.argv[1]).read()
print(json.dumps({"version": "2", "content": {"content": schema, "contentType": "application/json"}}))
' "${V2_FIXTURE}")"
V2_STATUS="$(curl -s -o "${LOG_DIR}/v2_response.json" -w '%{http_code}' -X POST \
    "${REGISTRY_BASE_URL}/groups/default/artifacts/${EVOLUTION_ARTIFACT_ID}/versions" \
    -H 'Content-Type: application/json' --data "${V2_BODY}")"
echo "  response: $(cat "${LOG_DIR}/v2_response.json")"
if [ "${V2_STATUS}" = "200" ]; then
    pass "v2 registered (HTTP ${V2_STATUS}) — adding a defaulted field is BACKWARD-compatible"
else
    fail "expected HTTP 200 registering v2, got ${V2_STATUS}"
fi

section "7. v3 — retypes totalCents long->string (BACKWARD-INCOMPATIBLE) — expect REJECTED (409)"
V3_BODY="$(python3 -c '
import json, sys
schema = open(sys.argv[1]).read()
print(json.dumps({"version": "3", "content": {"content": schema, "contentType": "application/json"}}))
' "${V3_FIXTURE}")"
V3_STATUS="$(curl -s -o "${LOG_DIR}/v3_response.json" -w '%{http_code}' -X POST \
    "${REGISTRY_BASE_URL}/groups/default/artifacts/${EVOLUTION_ARTIFACT_ID}/versions" \
    -H 'Content-Type: application/json' --data "${V3_BODY}")"
echo "  response: $(cat "${LOG_DIR}/v3_response.json")"
if [ "${V3_STATUS}" = "409" ] && grep -q "RuleViolationException" "${LOG_DIR}/v3_response.json"; then
    pass "v3 REJECTED (HTTP ${V3_STATUS}, RuleViolationException) — the incompatible retype was caught"
else
    fail "expected HTTP 409 + RuleViolationException rejecting v3, got HTTP ${V3_STATUS}"
fi

section "Demo complete"
if [ "${OVERALL_RESULT}" -eq 0 ]; then
    echo "RESULT: Avro + Apicurio round-trip proven, and BACKWARD compatibility genuinely enforces schema evolution (v2 accepted, v3 rejected)."
    echo "Reminder: the real order.placed JSON event flow (DRQ-038) was never touched by this script."
else
    echo "RESULT: one or more proofs did NOT behave as expected — see FAIL lines above." >&2
fi
echo "=================================================================="
exit "${OVERALL_RESULT}"
