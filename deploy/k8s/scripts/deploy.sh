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

step "Waiting for the eight services"
for entry in "${SERVICES[@]}"; do
  kc -n "$NAMESPACE" rollout status "deployment/${entry%%:*}" --timeout=420s
done

kc -n "$NAMESPACE" get pods
printf '\nEdge router: curl -s http://127.0.0.1:30888/api/inventory\nGrafana:     http://127.0.0.1:30300\n'
