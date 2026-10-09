#!/usr/bin/env bash
# openshift/mirror-infra-images.sh — copy the upstream Postgres and Kafka
# images into project `mea`'s ImageStreams.
#
# Why: on the verified CRC run the VM could not reach Docker Hub
# (registry-1.docker.io i/o timeout) while the host could. skopeo copies the
# images from the host straight into the internal registry's route: no
# container engine, TLS verified against the cluster's ingress CA (never
# --tls-verify=false), and a short-lived token for the project's `builder`
# ServiceAccount (system:image-builder) kept in a private authfile, never
# printed. On a cluster with Docker Hub egress, skip this and point
# values.yaml at the upstream refs.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_crc
command -v skopeo >/dev/null 2>&1 || fail "skopeo not in PATH (sudo dnf install -y skopeo)"

IMAGES=(
  "docker.io/library/postgres:18.6-alpine postgres:18.6-alpine"
  "docker.io/apache/kafka:4.3.1 kafka:4.3.1"
)

step "Expose the internal registry's default route (idempotent)"
occ patch configs.imageregistry.operator.openshift.io/cluster --type merge \
  -p '{"spec":{"defaultRoute":true}}' >/dev/null
for _ in $(seq 1 60); do
  REG="$(occ -n openshift-image-registry get route default-route -o jsonpath='{.spec.host}' 2>/dev/null || true)"
  [[ -n "$REG" ]] && break; sleep 2
done
[[ -n "${REG:-}" ]] || fail "registry default-route did not appear"
ok "registry route: $REG"

work="$(mktemp -d)"; chmod 700 "$work"
trap 'rm -rf "$work"' EXIT
occ get configmap default-ingress-cert -n openshift-config-managed \
  -o jsonpath='{.data.ca-bundle\.crt}' > "$work/ca.crt"
[[ -s "$work/ca.crt" ]] || fail "could not read the ingress CA (default-ingress-cert)"

token="$(occ -n "$NAMESPACE" create token builder --duration=15m)"
( umask 077
  printf '{"auths":{"%s":{"auth":"%s"}}}\n' "$REG" "$(printf 'builder:%s' "$token" | base64 -w0)" > "$work/auth.json" )
unset token

for pair in "${IMAGES[@]}"; do
  src="${pair% *}"; dst="${pair#* }"
  step "skopeo copy $src -> $REG/$NAMESPACE/$dst"
  skopeo copy --dest-cert-dir "$work" --dest-authfile "$work/auth.json" \
    "docker://$src" "docker://$REG/$NAMESPACE/$dst"
  occ -n "$NAMESPACE" get istag "$dst" >/dev/null || fail "ImageStreamTag $dst not found after the copy"
  ok "$NAMESPACE/$dst"
done
