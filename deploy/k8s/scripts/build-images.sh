#!/usr/bin/env bash
# deploy/k8s/scripts/build-images.sh — package each service, build its JVM
# image with Docker Engine and load it into the `mea` profile.
#
# `mvn package` -> `docker build -f src/main/docker/Dockerfile.jvm -t
# mea/<svc>:1.0` -> `minikube -p mea image load mea/<svc>:1.0`. No registry:
# the overlay sets imagePullPolicy: Never. A Deployment that already exists
# is restarted so it picks up the reloaded image.
#
# Usage:
#   deploy/k8s/scripts/build-images.sh                 # all eight services
#   deploy/k8s/scripts/build-images.sh order-service   # just these
# SKIP_MVN=1 reuses an existing target/quarkus-app.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require_docker_engine
require_tool minikube ""
require_tool mvn "sdk install maven"
require_profile_running

want=("$@")
for entry in "${SERVICES[@]}"; do
  svc="${entry%%:*}"; dir="$REPO_ROOT/examples/${entry#*:}"
  if (( ${#want[@]} )) && [[ ! " ${want[*]} " =~ " ${svc} " ]]; then continue; fi
  image="mea/${svc}:${IMAGE_TAG}"
  if [[ "${SKIP_MVN:-0}" != "1" ]]; then
    step "$svc: mvn package"
    mvn -B -q -ntp -f "$dir/pom.xml" package -DskipTests
  fi
  [[ -d "$dir/target/quarkus-app" ]] || fail "$dir/target/quarkus-app missing (run without SKIP_MVN=1)"
  step "$svc: docker build $image"
  docker build -q -f "$dir/src/main/docker/Dockerfile.jvm" -t "$image" "$dir" >/dev/null
  step "$svc: minikube -p $PROFILE image load $image"
  minikube -p "$PROFILE" image load "$image"
  minikube -p "$PROFILE" image ls 2>/dev/null | grep -qE "(^|/)mea/${svc}:${IMAGE_TAG}\$" \
    || fail "$image not present in the node after load"
  ok "$image loaded"
  if kc -n "$NAMESPACE" get deployment "$svc" >/dev/null 2>&1; then
    kc -n "$NAMESPACE" rollout restart deployment "$svc"
  fi
done
