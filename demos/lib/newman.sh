# demos/lib/newman.sh — resolve newman for the demos (sourced, not executed).
#
# DRQ-077 (2026-10-09): a host without a global `newman` no longer fails the
# demos. When `newman` is not on PATH, the CLI runs as a PINNED npx package
# (`npx -y newman@${NEWMAN_VERSION}`), which installs into the per-user npm
# cache (~/.npm/_npx) — no sudo, no global install. The Node-API helpers
# (lib/run-order-newman.js, lib/run-shipping-newman.js) get the same pinned
# package through NEWMAN_MODULE_DIR.
#
# Pin: newman 6.2.3, the newest non-prerelease on npm on 2026-10-09
# (`npm view newman dist-tags` -> latest: 6.2.3; engines node >=16).
# Override with NEWMAN_VERSION=<x.y.z> to test another release.
#
# Usage (from a demo script):
#   source "${SCRIPT_DIR}/lib/newman.sh"
#   newman_cli run <collection> ...      # CLI
#   newman_node_api || exit 2            # before `node lib/run-*-newman.js`

NEWMAN_VERSION="${NEWMAN_VERSION:-6.2.3}"

# newman_cli <args...> — the installed newman if present, else the pinned npx.
newman_cli() {
    if command -v newman >/dev/null 2>&1; then
        newman "$@"
    else
        npx -y "newman@${NEWMAN_VERSION}" "$@"
    fi
}

# newman_node_api — make require("newman") resolvable for the Node helpers.
# A locally or globally installed newman wins; otherwise export
# NEWMAN_MODULE_DIR pointing at the pinned npx package's module directory.
newman_node_api() {
    if [ -n "${NEWMAN_MODULE_DIR:-}" ] || node -e 'require("newman")' >/dev/null 2>&1; then
        return 0
    fi
    local bin
    bin="$(npx -y -p "newman@${NEWMAN_VERSION}" -c 'command -v newman' 2>/dev/null)" || bin=""
    if [ -z "${bin}" ]; then
        echo "error: could not fetch newman@${NEWMAN_VERSION} with npx (offline?); install it with: npm install -g newman@${NEWMAN_VERSION}" >&2
        return 1
    fi
    # .../node_modules/.bin/newman -> .../node_modules/newman/bin/newman.js
    NEWMAN_MODULE_DIR="$(dirname "$(dirname "$(readlink -f "${bin}")")")"
    export NEWMAN_MODULE_DIR
}
