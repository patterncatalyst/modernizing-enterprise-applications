#!/usr/bin/env python3
"""ch.31 figure: supply-chain-gate-before-after — the three-step gate
(SBOM -> scan -> policy) run over the same two artifacts examples/10's demo.sh
scans, and the verdict pair that is the chapter's headline evidence.

One pipeline, two inputs:
  * the Spring Boot monolith fat jar (the "before") -> FAIL (4 Critical, 6 High)
  * the extracted Quarkus order-service runtime closure (the "after") -> PASS (0/0)

The counts are the real captured 2026-10-06 run (grype DB built that day); they
drift as the DB updates, which is itself a point the figure makes — a gate is a
continuous check, not a one-time audit.

Sourced from: examples/10-supply-chain/demo.sh, policy.yaml, and the captured
evidence/summary.txt + evidence/*.grype.json.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1320, 620

# ---- two inputs ------------------------------------------------------------
mono = node(60, 120, 260, 110,
            ["monolith.jar  (before)", "Spring Boot 3.5 fat jar",
             "spring-webmvc / tomcat /", "jackson / postgresql"], style="box")
svc = node(60, 360, 260, 110,
           ["order-service  (after)", "Quarkus 3.40.1", "target/quarkus-app",
            "runtime closure"], style="accent")

# ---- the shared three-step gate (a band the two inputs flow through) --------
gate_band = {"x": 380, "y": 90, "w": 560, "h": 410,
             "label": "the gate  —  same three steps, local (demo.sh) and in CI (supply-chain.yml)",
             "fill": "#fafafa"}
sbom = node(410, 150, 240, 90, ["1 · syft", "CycloneDX SBOM", "the bill of materials"], style="sub")
scan = node(410, 270, 240, 90, ["2 · grype", "scan the SBOM", "vs current CVE DB"], style="sub")
policy = node(410, 390, 240, 90, ["3 · policy.yaml", "fail-on: High", "policy-as-code"], style="ink")

db = node(700, 270, 210, 90, ["vulnerability DB", "grype, built 2026-10-06", "a moving target"], style="kernel")

# ---- two verdicts ----------------------------------------------------------
fail = node(1000, 120, 260, 110,
            ["FAIL  (gate fires)", "4 Critical · 6 High · 8 Medium",
             "78 components", "non-zero exit -> CI red"], style="ink")
ok = node(1000, 360, 260, 110,
          ["PASS  (within policy)", "0 Critical · 0 High · 0 Medium",
           "522 components", "exit 0 -> CI green"], style="accent")

nodes = [mono, svc, sbom, scan, policy, db, fail, ok]

edges = [
    connect(mono, sbom, amber=True),
    connect(svc, sbom, amber=True),
    connect(sbom, scan, amber=True),
    connect(db, scan, dashed=True, label="feeds"),
    connect(scan, policy, amber=True),
    connect(policy, fail, amber=True, label="monolith"),
    connect(policy, ok, amber=True, label="order-service"),
]

notes = [
    {"x": W / 2, "y": 34,
     "text": "ch.31 — the supply-chain gate: the monolith fails it, the modernized service passes it",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 56,
     "text": "the modernization, made measurable — captured live 2026-10-06 (syft 1.44.0 / grype 0.112.0)",
     "anchor": "middle", "size": 11, "color": "#555555"},
    {"x": 60, "y": H - 22,
     "text": "Counts move as the vulnerability DB updates; the gate runs on every push, not once. Full output: examples/10-supply-chain/evidence/.",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit("supply-chain-gate-before-after", W, H, bands=[gate_band], nodes=nodes, edges=edges, notes=notes)
