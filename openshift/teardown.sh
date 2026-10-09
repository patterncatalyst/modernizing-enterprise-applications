#!/usr/bin/env bash
# openshift/teardown.sh — return OpenShift Local to empty after the appendix:
# Helm release, BuildConfigs/ImageStreams and PVCs (with the project), the
# project itself, the registry default route this appendix exposed, then
# `crc stop`. Pass --no-stop to leave CRC running.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
command -v oc >/dev/null 2>&1 || fail "oc not in PATH. Run: eval \"\$(crc oc-env)\""
server="$(occ whoami --show-server 2>/dev/null || true)"
[[ "$server" == *"api.crc.testing"* ]] || fail "context $OC_CONTEXT does not reach OpenShift Local"

if helm status mea --kube-context "$OC_CONTEXT" -n "$NAMESPACE" >/dev/null 2>&1; then
  step "helm uninstall mea"
  helm uninstall mea --kube-context "$OC_CONTEXT" -n "$NAMESPACE" --wait
fi
if occ get project "$NAMESPACE" >/dev/null 2>&1; then
  step "Deleting project $NAMESPACE (PVCs, BuildConfigs, ImageStreams with it)"
  occ delete project "$NAMESPACE" --wait=false
  for _ in $(seq 1 90); do
    occ get namespace "$NAMESPACE" >/dev/null 2>&1 || break; sleep 2
  done
  occ get namespace "$NAMESPACE" >/dev/null 2>&1 && fail "namespace $NAMESPACE still terminating; inspect: oc --context $OC_CONTEXT get ns $NAMESPACE -o yaml"
  ok "project $NAMESPACE removed"
fi
step "Hiding the registry default route again"
occ patch configs.imageregistry.operator.openshift.io/cluster --type merge \
  -p '{"spec":{"defaultRoute":false}}' >/dev/null

if [[ "${1:-}" != "--no-stop" ]]; then
  step "crc stop"
  crc stop
fi
