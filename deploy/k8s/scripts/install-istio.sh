#!/usr/bin/env bash
# deploy/k8s/scripts/install-istio.sh — install Istio 1.31.1 into the `mea`
# profile from deploy/k8s/istio/istio-install.yaml (Chapter 30).
#
# istioctl must be exactly 1.31.1 (the client installs its own version).
# Download: https://github.com/istio/istio/releases/tag/1.31.1
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_tool istioctl "Install istioctl $ISTIO_VERSION: curl -L https://istio.io/downloadIstio | ISTIO_VERSION=$ISTIO_VERSION sh -"
require_profile_running

client="$(istioctl version --remote=false 2>/dev/null | awk '{print $NF}')"
[[ "$client" == "$ISTIO_VERSION" ]] || fail "istioctl is $client; this chapter pins $ISTIO_VERSION"

step "istioctl install (Istio $ISTIO_VERSION, context $PROFILE)"
istioctl install -y --context "$PROFILE" -f "$REPO_ROOT/deploy/k8s/istio/istio-install.yaml"
kc -n istio-system rollout status deployment/istiod --timeout=300s
kc -n istio-system rollout status deployment/istio-ingressgateway --timeout=300s
ok "Istio $ISTIO_VERSION ready"
