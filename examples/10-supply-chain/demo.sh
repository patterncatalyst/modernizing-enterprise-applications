#!/usr/bin/env bash
# ch.31 -- Supply-chain gate: SBOM + CVE scan + policy-as-code.
#
# For each artifact named in policy.yaml this script:
#   1. generates a CycloneDX SBOM with syft (the bill of materials), and
#   2. scans that SBOM with grype against the current vulnerability DB, and
#   3. applies the policy-as-code threshold (fail-on) from policy.yaml.
#
# It is deliberately the *same* three steps the CI workflow
# (.github/workflows/supply-chain.yml) runs, so "it passed locally" and "it
# passed in CI" mean the same thing. Evidence (SBOMs + scan JSON + a summary)
# is written to evidence/ so the run is auditable after the fact.
#
# Prereqs: syft and grype on PATH; the two target artifacts already built
# (the script builds them if missing). No network needed beyond grype's DB,
# which it refreshes on first use.
set -euo pipefail

cd "$(dirname "$0")"
REPO_ROOT="$(cd ../.. && pwd)"
EVIDENCE="evidence"
mkdir -p "$EVIDENCE"

for tool in syft grype; do
  command -v "$tool" >/dev/null 2>&1 || { echo "FATAL: '$tool' not on PATH -- see README.md (Prerequisites)"; exit 2; }
done

# --- read policy.yaml without a YAML dependency (small, fixed shape) ---------
FAIL_ON="$(grep -E '^fail-on:' policy.yaml | awk '{print $2}')"
echo "Policy: fail the gate on >= ${FAIL_ON}   (source: policy.yaml)"
echo "grype DB: $(grype db status 2>/dev/null | awk -F': +' '/^Built/{print $2}')"
echo

# grype's own gate: --fail-on returns a non-zero exit when a finding at or
# above the threshold exists. We capture that per-target instead of letting it
# abort the loop, so the summary reports every target even after one fails.
summary="$EVIDENCE/summary.txt"
: > "$summary"
gate_failed=0

scan_target() {
  local name="$1" kind="$2" path="$3"
  local src="$path"
  echo "=== [$kind] $name -> $path ==="
  if [ ! -e "$REPO_ROOT/$path" ]; then
    echo "  artifact missing; building it..."
    if [ "$name" = "monolith" ]; then
      ( cd "$REPO_ROOT/examples/00-monolith" && mvn -q -o package -DskipTests )
    else
      ( cd "$REPO_ROOT/examples/07-order-service" && mvn -q -o package -DskipTests )
    fi
  fi

  local sbom="$EVIDENCE/${name}.sbom.cdx.json"
  local scan="$EVIDENCE/${name}.grype.json"

  # 1. SBOM -- CycloneDX JSON, the portable bill of materials. syft
  #    auto-detects the source type: a fat jar (the monolith) is read as an
  #    archive, a directory (the Quarkus runtime closure) is walked in place.
  syft "$REPO_ROOT/$path" -o "cyclonedx-json=$sbom" -q
  local components
  components="$(jq '.components | length' "$sbom")"

  # 2. scan the SBOM (not the artifact again) -- the SBOM is the contract.
  grype "sbom:$sbom" -o json -q > "$scan"

  # 3. tally by severity and apply the policy.
  local crit high med
  crit="$(jq '[.matches[]|select(.vulnerability.severity=="Critical")]|length' "$scan")"
  high="$(jq '[.matches[]|select(.vulnerability.severity=="High")]|length' "$scan")"
  med="$(jq '[.matches[]|select(.vulnerability.severity=="Medium")]|length' "$scan")"

  local verdict="PASS"
  # fail-on: High means Critical or High trips the gate.
  if { [ "$FAIL_ON" = "High" ] && { [ "$crit" -gt 0 ] || [ "$high" -gt 0 ]; }; } \
     || { [ "$FAIL_ON" = "Critical" ] && [ "$crit" -gt 0 ]; }; then
    verdict="FAIL"
    gate_failed=1
  fi

  printf '  components=%s  Critical=%s  High=%s  Medium=%s  -> %s\n' \
    "$components" "$crit" "$high" "$med" "$verdict"
  printf '%-14s [%-6s] components=%-4s Critical=%-2s High=%-2s Medium=%-2s %s\n' \
    "$name" "$kind" "$components" "$crit" "$high" "$med" "$verdict" >> "$summary"
  echo
}

# iterate policy.yaml targets (fixed three-line blocks)
while IFS= read -r line; do
  case "$line" in
    *"- name:"*) name="$(echo "$line" | awk '{print $3}')" ;;
    *"kind:"*)   kind="$(echo "$line" | awk '{print $2}')" ;;
    *"path:"*)   path="$(echo "$line" | awk '{print $2}')"; scan_target "$name" "$kind" "$path" ;;
  esac
done < <(sed -n '/^targets:/,$p' policy.yaml)

echo "===================== gate summary ====================="
cat "$summary"
echo "========================================================"
if [ "$gate_failed" -ne 0 ]; then
  echo "GATE: FAIL -- at least one artifact breaches policy (fail-on: ${FAIL_ON})."
  echo "      (This is the expected result for the monolith 'before' artifact.)"
  exit 1
fi
echo "GATE: PASS -- every artifact is within policy."
