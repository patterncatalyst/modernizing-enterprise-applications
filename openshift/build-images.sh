#!/usr/bin/env bash
# openshift/build-images.sh — build the eight service images INSIDE the
# cluster. No container engine on the host, no exposed or insecure registry.
#
# Per service: the host runs `mvn package` (fast-jar in target/quarkus-app);
# a Docker-strategy binary BuildConfig in project `mea` receives a minimal
# context (target/quarkus-app + the service's src/main/docker/Dockerfile.jvm
# as ./Dockerfile) via `oc start-build --from-dir`, builds it on
# ubi10/openjdk-25-runtime and pushes <svc>:1.0 to the ImageStream the chart
# pulls (image-registry.openshift-image-registry.svc:5000/mea/<svc>:1.0).
#
# Usage:
#   openshift/build-images.sh                  # all eight
#   openshift/build-images.sh order-service    # just these
# SKIP_MVN=1 reuses an existing target/quarkus-app.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_crc
command -v mvn >/dev/null 2>&1 || fail "mvn not in PATH"

want=("$@")
for entry in "${SERVICES[@]}"; do
  svc="${entry%%:*}"; dir="$REPO_ROOT/examples/${entry#*:}"
  if (( ${#want[@]} )) && [[ ! " ${want[*]} " =~ " ${svc} " ]]; then continue; fi
  if [[ "${SKIP_MVN:-0}" != "1" ]]; then
    step "$svc: mvn package"
    mvn -B -q -ntp -f "$dir/pom.xml" package -DskipTests
  fi
  [[ -d "$dir/target/quarkus-app" ]] || fail "$dir/target/quarkus-app missing"

  ctx="$(mktemp -d)"
  trap 'rm -rf "$ctx"' EXIT
  mkdir -p "$ctx/target"
  cp -r "$dir/target/quarkus-app" "$ctx/target/"
  cp "$dir/src/main/docker/Dockerfile.jvm" "$ctx/Dockerfile"

  if ! occ -n "$NAMESPACE" get bc "$svc" >/dev/null 2>&1; then
    step "$svc: create Docker-strategy binary BuildConfig -> $svc:$IMAGE_TAG"
    occ -n "$NAMESPACE" new-build --binary --strategy=docker --name="$svc" --to="$svc:$IMAGE_TAG"
  fi
  step "$svc: oc start-build --from-dir (in-cluster image build)"
  occ -n "$NAMESPACE" start-build "$svc" --from-dir="$ctx" --follow --wait
  rm -rf "$ctx"; trap - EXIT
  occ -n "$NAMESPACE" get istag "$svc:$IMAGE_TAG" >/dev/null || fail "ImageStreamTag $svc:$IMAGE_TAG not found after the build"
  ok "$svc:$IMAGE_TAG pushed to the internal registry"
  if occ -n "$NAMESPACE" get deployment "$svc" >/dev/null 2>&1; then
    occ -n "$NAMESPACE" rollout restart deployment "$svc"
  fi
done
