#!/usr/bin/env python3
"""ch.31 figure: delivery-pipeline-ci-gates — the GitHub Actions pipeline this
repository actually ships, drawn honestly against what is real vs. deferred.

A git push / PR fans into three workflow files that exist in .github/workflows/:
  * code-ci.yml  — the BEHAVIOR gates: seven path-filtered jobs (equivalence per
                   seam + the order/gateway contract gate + the schema-registry
                   gate) that run a service and drive the Newman suite.
  * supply-chain.yml — the PROVENANCE gate (ch.31, additive): build -> SBOM
                   (syft/CycloneDX) -> scan (grype) -> policy (fail-on High).
  * pages.yml    — builds and deploys the Jekyll site to GitHub Pages.

Drawn as ghost (dashed) boxes on the right, the honestly-deferred pieces that a
full GitOps delivery chain would add but this repo does not yet have: pushing
built images to a registry, a pull-based GitOps operator (Argo CD / Flux)
reconciling deploy/k8s into the cluster, and Istio weighted canary (ch.30
deferred all Istio traffic-shaping). No codenames; file names are real.

Sourced from: .github/workflows/code-ci.yml (the seven job names),
supply-chain.yml, pages.yml; examples/10-supply-chain/{demo.sh,policy.yaml}.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1480, 920

trigger = node(60, 400, 230, 90,
               ["git push / PR", "any branch", "(path-filtered jobs)"], style="ink")

# ---- lane 1: behavior gates (code-ci.yml) ----------------------------------
behavior_band = {"x": 360, "y": 70, "w": 700, "h": 360,
                 "label": "code-ci.yml  —  behavior gates (what the system DOES)", "fill": "#fafafa"}
equiv = node(390, 120, 300, 80,
             ["equivalence-gate", "monolith baseline — Newman suite"], style="accent")
seams = node(390, 215, 300, 120,
             ["notification / inventory /", "payment / shipping",
              "-equivalence-gate", "one per extracted seam"], style="box")
contract = node(730, 120, 300, 80,
                ["order-gateway-contract-gate", "CQRS + GraphQL aggregation"], style="box")
schema = node(730, 215, 300, 120,
              ["schema-registry-gate", "Avro + Apicurio demonstrator",
               "builds examples/09"], style="box")
behavior_note = node(390, 350, 640, 60,
                     ["Each gate boots the service(s) + infra, then drives the",
                      "behavior-equivalence suite. Green is the merge bar."], style="kernel")

# ---- lane 2: provenance gate (supply-chain.yml) ----------------------------
prov_band = {"x": 360, "y": 460, "w": 700, "h": 200,
             "label": "supply-chain.yml  —  provenance gate (what the system CONTAINS)  [ch.31, additive]",
             "fill": "#f4f8f5"}
build = node(385, 520, 150, 110, ["mvn package", "runtime closure"], style="sub")
sbom = node(555, 520, 160, 110, ["syft", "CycloneDX SBOM"], style="accent")
scan = node(735, 520, 150, 110, ["grype", "scan vs CVE DB"], style="box")
policy = node(905, 520, 135, 110, ["policy.yaml", "fail-on: High"], style="ink")

# ---- lane 3: site (pages.yml) ----------------------------------------------
pages = node(360, 690, 700, 70,
             ["pages.yml  —  build + deploy the Jekyll site to GitHub Pages"], style="sub")

# ---- deferred (ghost) : the rest of a GitOps delivery chain ----------------
deferred_band = {"x": 1120, "y": 300, "w": 320, "h": 360,
                 "label": "deferred (named, not built)", "fill": "#ffffff"}
registry = node(1145, 350, 270, 80, ["image registry push", "ghcr.io / quay.io"], style="ghost")
gitops = node(1145, 450, 270, 90, ["GitOps operator", "Argo CD / Flux reconciles", "deploy/k8s -> cluster"], style="ghost")
canary = node(1145, 560, 270, 80, ["Istio weighted canary", "(ch.30 deferred traffic-shaping)"], style="ghost")

nodes = [trigger, equiv, seams, contract, schema, behavior_note,
         build, sbom, scan, policy, pages,
         registry, gitops, canary]

edges = [
    connect(trigger, equiv, amber=True),
    connect(trigger, build, amber=True),
    connect(trigger, pages, amber=True),
    connect(build, sbom, amber=True),
    connect(sbom, scan, amber=True),
    connect(scan, policy, amber=True),
    # the deferred continuation, dashed
    connect(policy, registry, dashed=True, label="would feed"),
    connect(registry, gitops, dashed=True),
    connect(gitops, canary, dashed=True),
]

notes = [
    {"x": W / 2, "y": 34,
     "text": "ch.31 — the delivery pipeline this repo ships: behavior gates + a provenance gate, drawn against what is deferred",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": 60, "y": H - 24,
     "text": "Real: code-ci.yml (7 jobs), supply-chain.yml, pages.yml. Dashed: a registry push + GitOps operator + Istio canary this repo names but does not yet run.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit("delivery-pipeline-ci-gates", W, H, bands=[behavior_band, prov_band, deferred_band], nodes=nodes, edges=edges, notes=notes)
