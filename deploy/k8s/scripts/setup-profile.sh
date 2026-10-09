#!/usr/bin/env bash
# deploy/k8s/scripts/setup-profile.sh — create (or start) the `mea` minikube
# profile for Chapters 29-30.
#
# Docker Engine + `--driver=docker --container-runtime=containerd`,
# Kubernetes v1.36.5 passed explicitly. The NodePorts in lib.sh are published
# on 127.0.0.1 at creation (`--ports=127.0.0.1:<p>:<p>`), so the edge router
# is http://127.0.0.1:30888 and Grafana http://127.0.0.1:30300 — no
# port-forward, no tunnel, no `minikube service`. Ports are fixed at
# creation: changing them means --replace.
#
# One local cluster at a time: refuses to run while another minikube profile
# or CRC is running.
#
# Usage:
#   deploy/k8s/scripts/setup-profile.sh             # create, start, or verify
#   deploy/k8s/scripts/setup-profile.sh --replace   # delete first, then create
# Sizing: MINIKUBE_MEMORY (default 12g), MINIKUBE_CPUS (6), MINIKUBE_DISK (40g).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MEMORY="${MINIKUBE_MEMORY:-12g}"
CPUS="${MINIKUBE_CPUS:-6}"
DISK="${MINIKUBE_DISK:-40g}"
REPLACE=0; [[ "${1:-}" == "--replace" ]] && REPLACE=1

require_docker_engine
require_tool minikube "Install minikube ${MINIKUBE_MIN}+."
require_tool kubectl "Install kubectl 1.36.x."
require_tool python3 "sudo dnf install -y python3"

mk_ver="$(minikube version --short 2>/dev/null || echo v0.0.0)"
[[ "$(printf '%s\n%s\n' "$MINIKUBE_MIN" "$mk_ver" | sort -V | head -1)" == "$MINIKUBE_MIN" ]] \
  || fail "minikube $mk_ver is older than $MINIKUBE_MIN"

inotify="$(sysctl -n fs.inotify.max_user_instances 2>/dev/null || echo 0)"
(( inotify >= 256 )) || fail "fs.inotify.max_user_instances is $inotify (need >= 256):
  printf 'fs.inotify.max_user_instances = 512\nfs.inotify.max_user_watches = 524288\n' | sudo tee /etc/sysctl.d/99-kubernetes.conf
  sudo sysctl -p /etc/sysctl.d/99-kubernetes.conf"

# One cluster at a time.
others="$(minikube profile list -o json 2>/dev/null | python3 -c '
import json, sys
try: data = json.load(sys.stdin) or {}
except Exception: data = {}
for p in data.get("valid") or []:
    if p.get("Name") != sys.argv[1] and p.get("Status") == "Running":
        print(p["Name"])
' "$PROFILE" || true)"
[[ -z "$others" ]] || fail "other minikube profiles are running: $(echo $others). Stop them first: minikube stop -p <name>"
if command -v crc >/dev/null 2>&1 && crc status 2>/dev/null | grep -qE 'CRC VM:\s+Running'; then
  fail "CRC is running. One local cluster at a time: stop it first (crc stop)."
fi

PORTS_ARG=""; for p in "${NODE_PORTS[@]}"; do PORTS_ARG+="${PORTS_ARG:+,}127.0.0.1:${p}:${p}"; done

exists="$(minikube profile list -o json 2>/dev/null | python3 -c '
import json, sys
try: data = json.load(sys.stdin) or {}
except Exception: data = {}
names = [p.get("Name") for p in (data.get("valid") or []) + (data.get("invalid") or [])]
print("yes" if sys.argv[1] in names else "no")
' "$PROFILE" || echo no)"

if [[ "$exists" == "yes" && $REPLACE -eq 1 ]]; then
  step "Deleting profile $PROFILE (--replace)"
  minikube delete -p "$PROFILE"
  exists=no
fi

if [[ "$exists" == "yes" ]]; then
  if minikube status -p "$PROFILE" >/dev/null 2>&1; then
    step "Profile $PROFILE already running"
  else
    step "Starting stopped profile $PROFILE (published ports persist)"
    minikube start -p "$PROFILE"
  fi
else
  for p in "${NODE_PORTS[@]}"; do
    [[ -z "$(ss -ltnH "sport = :$p" 2>/dev/null)" ]] || fail "host port $p is in use; free it and re-run"
  done
  step "Creating $PROFILE: Kubernetes $K8S_VERSION, $MEMORY / $CPUS CPUs / $DISK, docker driver, containerd, ports $PORTS_ARG"
  minikube start -p "$PROFILE" \
    --driver=docker \
    --container-runtime=containerd \
    --kubernetes-version="$K8S_VERSION" \
    --memory="$MEMORY" --cpus="$CPUS" --disk-size="$DISK" \
    --ports="$PORTS_ARG"
fi

step "Verifying published NodePorts"
for p in "${NODE_PORTS[@]}"; do
  docker port "$PROFILE" "${p}/tcp" 2>/dev/null | grep -qF "127.0.0.1:${p}" \
    || fail "profile $PROFILE does not publish $p on 127.0.0.1. Ports are fixed at creation: $0 --replace"
  ok "127.0.0.1:${p}"
done

kc get nodes
printf '\nNext: deploy/k8s/scripts/install-istio.sh && deploy/k8s/scripts/build-images.sh && deploy/k8s/scripts/deploy.sh\n'
