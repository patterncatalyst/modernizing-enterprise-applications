# deploy/k8s/scripts/lib.sh — shared settings and checks for the minikube path.
# Sourced, not executed.
#
# One profile (`mea`), the docker driver with the containerd runtime, on
# Docker Engine (docker-ce, context `default`). Every call names the profile
# explicitly (`minikube -p`, `kubectl --context`); kubectl's current-context
# is never read or changed, and `minikube config set` is never used.

PROFILE="${MEA_PROFILE:-mea}"
NAMESPACE="mea"
K8S_VERSION="${KUBERNETES_VERSION:-v1.36.5}"
ISTIO_VERSION="1.31.1"
IMAGE_TAG="1.0"
MINIKUBE_MIN="v1.39.0"
DOCKER_SOCK="unix:///var/run/docker.sock"

# NodePorts published to the host on 127.0.0.1 at profile creation
# (host port = nodePort). Keep in sync with
# deploy/k8s/overlays/minikube/kustomization.yaml (strangler-proxy) and
# deploy/k8s/observability/lgtm-service.yaml (lgtm-grafana).
NODE_PORTS=(30888 30300)   # strangler-proxy REST edge, Grafana

# The eight workloads: <service name>:<examples/ directory>.
SERVICES=(
  strangler-proxy:01-strangler-proxy
  review-service:02-review-service
  notification-service:03-notification-service
  inventory-service:04-inventory-service
  payment-service:05-payment-service
  shipping-service:06-shipping-service
  order-service:07-order-service
  graphql-gateway:08-graphql-gateway
)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

step() { printf '\n==> %s\n' "$*"; }
ok()   { printf '    ok: %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

kc() { command kubectl --context "$PROFILE" "$@"; }

require_tool() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 not in PATH. $2"
}

require_docker_engine() {
  require_tool docker "Install Docker Engine (docker-ce) — see the Prerequisites chapter."
  if rpm -q podman-docker >/dev/null 2>&1; then
    fail "podman-docker shadows the docker CLI. Fix: sudo dnf remove podman-docker"
  fi
  local ctx endpoint
  ctx="$(docker context show 2>/dev/null || true)"
  [[ "$ctx" == "default" ]] || fail "docker context is '${ctx:-unknown}', expected 'default'. Fix: docker context use default"
  endpoint="$(docker context inspect default --format '{{.Endpoints.docker.Host}}' 2>/dev/null || true)"
  [[ "$endpoint" == "$DOCKER_SOCK" ]] || fail "docker context 'default' points at '${endpoint:-unknown}', expected $DOCKER_SOCK"
  if [[ -n "${DOCKER_HOST:-}" && "$DOCKER_HOST" != "$DOCKER_SOCK" ]]; then
    fail "DOCKER_HOST is '$DOCKER_HOST'; unset it (expected $DOCKER_SOCK or unset)"
  fi
  docker info >/dev/null 2>&1 || fail "cannot reach the Docker daemon. Fix: sudo systemctl enable --now docker; sudo usermod -aG docker \$USER (log out and back in)"
}

require_profile_running() {
  minikube status -p "$PROFILE" >/dev/null 2>&1 \
    || fail "minikube profile $PROFILE is not running. Start it: deploy/k8s/scripts/setup-profile.sh"
}
