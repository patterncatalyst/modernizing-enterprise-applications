# openshift/lib.sh — shared settings for the OpenShift appendix scripts.
# Sourced, not executed.
#
# Every call goes through the `crc-admin` kubeconfig context that `crc start`
# writes (override with OC_CONTEXT), and refuses any API server other than
# OpenShift Local's. No passwords or tokens are printed.

OC_CONTEXT="${OC_CONTEXT:-crc-admin}"
NAMESPACE="${MEA_NAMESPACE:-mea}"
IMAGE_TAG="1.0"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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

step() { printf '\n==> %s\n' "$*"; }
ok()   { printf '    ok: %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

occ() { command oc --context "$OC_CONTEXT" "$@"; }

require_crc() {
  command -v oc >/dev/null 2>&1 || fail "oc not in PATH. Run: eval \"\$(crc oc-env)\""
  command oc config get-contexts "$OC_CONTEXT" >/dev/null 2>&1 \
    || fail "no kubeconfig context $OC_CONTEXT. Run crc start (it writes crc-admin)."
  local server
  server="$(occ whoami --show-server 2>/dev/null || true)"
  [[ "$server" == *"api.crc.testing"* ]] \
    || fail "context $OC_CONTEXT reaches '${server:-nothing}', not OpenShift Local (api.crc.testing). Is CRC running?"
  occ get project "$NAMESPACE" >/dev/null 2>&1 \
    || fail "project $NAMESPACE does not exist. Create it: oc --context $OC_CONTEXT new-project $NAMESPACE"
}
