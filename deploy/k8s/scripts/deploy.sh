#!/usr/bin/env bash
# deploy/k8s/scripts/deploy.sh — apply the minikube overlay (base + Istio CRs
# + LGTM) to the `mea` profile and wait for every workload to roll out.
# Run install-istio.sh and build-images.sh first.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_tool kubectl ""
require_profile_running
kc get crd peerauthentications.security.istio.io >/dev/null 2>&1 \
  || fail "Istio CRDs missing. Run deploy/k8s/scripts/install-istio.sh first."

step "kubectl --context $PROFILE apply -k deploy/k8s/overlays/minikube"
kc apply -k "$REPO_ROOT/deploy/k8s/overlays/minikube"

step "Waiting for infra (StatefulSets) and the LGTM backend"
kc -n "$NAMESPACE" rollout status statefulset/postgres --timeout=300s
kc -n "$NAMESPACE" rollout status statefulset/kafka --timeout=300s
kc -n "$NAMESPACE" rollout status deployment/lgtm --timeout=300s

# The decommissioned monolith's tables in `public` are still read by the
# running topology (review-service reads public.reviews; the demos read
# public.orders). Apply its committed Flyway SQL once, on a fresh database,
# exactly as compose (infra/db/init/10-monolith-public-schema.sh) and CI do.
step "Bootstrapping the monolith's public schema (fresh database only)"
if [[ "$(kc -n "$NAMESPACE" exec statefulset/postgres -- psql -U monolith -d monolith -tAc "SELECT to_regclass('public.reviews') IS NULL")" == "t" ]]; then
  for f in $(ls "$REPO_ROOT"/examples/00-monolith/src/main/resources/db/migration/V*__*.sql | sort -V); do
    echo "  applying $(basename "$f")"
    kc -n "$NAMESPACE" exec -i statefulset/postgres -- psql -U monolith -d monolith -v ON_ERROR_STOP=1 -q < "$f"
  done
else
  echo "  public schema already present"
fi

step "Waiting for the eight services"
for entry in "${SERVICES[@]}"; do
  kc -n "$NAMESPACE" rollout status "deployment/${entry%%:*}" --timeout=420s
done

# order-service owns its customers table and it starts EMPTY by design
# (DRQ-073: forward-filled only, no backfill). The equivalence suite's
# fixtures order as customer id=1, so seed that one row the same way
# demos/demo-final-topology.sh and CI do -- idempotent, not a migration.
step "Seeding the suite's customer fixture (order_service.customers id=1)"
kc -n "$NAMESPACE" exec statefulset/postgres -- psql -U monolith -d monolith -v ON_ERROR_STOP=1 -q -c \
  "INSERT INTO order_service.customers (id, name, email, created_at) VALUES (1, 'Ada Lovelace', 'ada@example.com', now()) ON CONFLICT (id) DO NOTHING; SELECT setval(pg_get_serial_sequence('order_service.customers','id'), (SELECT MAX(id) FROM order_service.customers));" >/dev/null

kc -n "$NAMESPACE" get pods
printf '\nEdge router: curl -s http://127.0.0.1:30888/api/inventory\nGrafana:     http://127.0.0.1:30300\n'
