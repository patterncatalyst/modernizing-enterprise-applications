#!/usr/bin/env bash
# demo-cutover.sh — demonstrate the Review strangler-fig CUTOVER + DECOMMISSION
# (r02-plan S10, build-plan.md §E step 1).
#
# NOTE: this reconstructs an INTERMEDIATE migration state and depends on the
# frozen examples/00-monolith (running on :8080) to show the strangler cutover
# against it. In the FINISHED system the monolith is decommissioned — see
# demos/demo-final-topology.sh for the end-state (flagless edge router, no
# monolith).
#
# This is a narration + verification script, not a service launcher: it
# assumes the three r02 services are already built and running (see
# "Prerequisites" below) and walks through the same evidence trail captured
# during r02/S10:
#
#   1. The strangler proxy (examples/01-strangler-proxy, :8888) is reachable,
#      with its committed default strangler.review.enabled=true.
#   2. Content-based routing actually discriminates by backend: GET
#      /api/reviews/** is served by the extracted Quarkus review-service
#      (:8081), while every other /api/** path is still served by the
#      monolith (:8080) — proven here by comparing responses, not just status
#      codes (an earlier version of the route's predicate NEVER matched this
#      path and silently always went to the monolith; see CUTOVER.md "Bug
#      found and fixed during S10" for the full story).
#   3. The monolith itself 404s on /api/reviews directly (:8080) — the Review
#      module (controller/service/repository/entity) has been decommissioned.
#   4. The full behavior-equivalence suite (the Newman collection,
#      tooling/newman/mea.postman_collection.json) is run, unchanged, through
#      the proxy — expected 49/49 assertions green, with Review served by
#      Quarkus and everything else by the five-context monolith.
#
# Prerequisites (not started by this script):
#   - compose stack up (mea-postgres on :5432)
#   - monolith running on :8080            (examples/00-monolith)
#   - review-service running on :8081      (examples/02-review-service)
#   - strangler proxy running on :8888     (examples/01-strangler-proxy)
#     with its committed default (strangler.review.enabled=true) — no -D
#     override needed; see CUTOVER.md for how to run it with the flag off to
#     observe the (now closed) reversibility window.
#
# Usage:
#   demos/demo-cutover.sh [proxyBaseUrl] [monolithBaseUrl] [reviewServiceBaseUrl]
#   demos/demo-cutover.sh                                   # defaults below

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

PROXY_URL="${1:-http://localhost:8888}"
MONOLITH_URL="${2:-http://localhost:8080}"
REVIEW_SERVICE_URL="${3:-http://localhost:8081}"

pass() { echo "  OK   $*"; }
fail() { echo "  FAIL $*" >&2; exit 1; }

echo "== r02/S10 — Review strangler-fig cutover + decommission demo =========="
echo "proxy          : ${PROXY_URL}"
echo "monolith       : ${MONOLITH_URL}"
echo "review-service : ${REVIEW_SERVICE_URL}"
echo "=========================================================================="

echo
echo "-- 1. The monolith no longer serves Review directly (decommissioned) ---"
status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${MONOLITH_URL}/api/reviews")"
if [ "${status}" = "404" ]; then
    pass "GET ${MONOLITH_URL}/api/reviews -> 404 (Review module removed from the monolith)"
else
    fail "expected 404 from the monolith's /api/reviews, got ${status}. Is the monolith still carrying its Review module?"
fi

echo
echo "-- 2. The monolith still serves its other five contexts ----------------"
for path in /api/orders /api/inventory; do
    status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${MONOLITH_URL}${path}")"
    [ "${status}" = "200" ] && pass "GET ${MONOLITH_URL}${path} -> 200" \
        || fail "expected 200 from ${MONOLITH_URL}${path}, got ${status}"
done

echo
echo "-- 3. The proxy's content-based routing discriminates correctly --------"
echo "   (comparing the proxy's Review response against review-service directly;"
echo "    a prior routing bug made the proxy silently ALWAYS target the monolith"
echo "    regardless of the flag — see CUTOVER.md.)"
proxy_reviews="$(curl -s --max-time 5 "${PROXY_URL}/api/reviews?sku=SKU-WIDGET-001")"
direct_reviews="$(curl -s --max-time 5 "${REVIEW_SERVICE_URL}/api/reviews?sku=SKU-WIDGET-001")"
if [ "${proxy_reviews}" = "${direct_reviews}" ]; then
    pass "proxy's /api/reviews body matches review-service's response byte-for-byte"
else
    fail "proxy's /api/reviews response diverges from review-service — is the proxy actually routing Review traffic to Quarkus?"
fi

echo
echo "-- 4. The full behavior-equivalence suite, through the proxy -----------"
"${SCRIPT_DIR}/demo-equivalence.sh" "${PROXY_URL}"

echo
echo "=========================================================================="
echo "Cutover + decommission demo complete."
echo "  - strangler.review.enabled=true is the committed default (permanent cutover)."
echo "  - Review is served by examples/02-review-service, everything else by the"
echo "    slimmed (five-context) examples/00-monolith."
echo "  - Flipping the flag back to false today only reaches a monolith with"
echo "    nothing left to serve at /api/reviews/** — the reversibility window"
echo "    that existed through r02/S7-S9 is now closed by design (decommission"
echo "    is the one deliberately irreversible step in the sequence)."
echo "See examples/01-strangler-proxy/CUTOVER.md for the full evidence trail."
echo "=========================================================================="
