#!/usr/bin/env bash
# openshift/deploy.sh — helm upgrade --install the `mea` chart into project
# `mea` and wait on every rollout (not condition=Available, which an old
# ReplicaSet satisfies during an upgrade). Prints the Route checks.
# Run mirror-infra-images.sh and build-images.sh first.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_crc
command -v helm >/dev/null 2>&1 || fail "helm not in PATH"

step "helm upgrade --install mea (context $OC_CONTEXT, namespace $NAMESPACE)"
helm upgrade --install mea "$REPO_ROOT/openshift/helm/mea" \
  --kube-context "$OC_CONTEXT" --namespace "$NAMESPACE" "$@"

step "Waiting for Postgres and Kafka"
occ -n "$NAMESPACE" rollout status statefulset/postgres --timeout=300s
occ -n "$NAMESPACE" rollout status statefulset/kafka --timeout=300s
step "Waiting for the eight services"
for entry in "${SERVICES[@]}"; do
  occ -n "$NAMESPACE" rollout status "deployment/${entry%%:*}" --timeout=600s
done
occ -n "$NAMESPACE" get pods,route

cat <<MSG

Verify through the Routes with the cluster's ingress CA (never --insecure):
  oc --context $OC_CONTEXT get configmap default-ingress-cert -n openshift-config-managed \\
     -o jsonpath='{.data.ca-bundle\\.crt}' > /tmp/ingress-ca.crt
  curl -s --cacert /tmp/ingress-ca.crt https://graphql-gateway-$NAMESPACE.apps-crc.testing/q/health/ready
  curl -s --cacert /tmp/ingress-ca.crt https://strangler-proxy-$NAMESPACE.apps-crc.testing/api/inventory
MSG
