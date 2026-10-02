#!/usr/bin/env python3
"""ch.15 figure: review-two-phase-migration — Phase A -> Phase B -> native.

Compact flow of the repeatable per-service migration template (build-plan.md
SS E, DRQ-029) as taught on the Review service: Phase A lifts the Spring-API
source onto Quarkus largely unchanged via the Spring-compatibility
extensions; Phase B strips that shim for idiomatic Quarkus (REST/Panache/
CDI); a native-image build is the measured "why Quarkus" payoff. The metrics
band below is the real before/after from examples/02-review-service/MIGRATION.md
(same Postgres, profile=prod) -- not placeholders.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1180, 560

phase_a = node(40, 130, 310, 120,
                ["Phase A — lift onto Quarkus", "Spring-compat extensions",
                 "quarkus-spring-web / -di / -data-jpa"], style="box")
phase_b = node(430, 130, 310, 120,
                ["Phase B — idiomatic Quarkus", "Quarkus REST + Panache + CDI",
                 "compat shim removed"], style="accent")
native = node(820, 130, 310, 120,
              ["Phase B — native image", "GraalVM Mandrel builder image",
               "same idiomatic code, no rewrite"], style="ink")

nodes = [phase_a, phase_b, native]
edges = [
    connect(phase_a, phase_b, label="refactor off Spring-compat (DRQ-029)", lx=0, ly=-75),
    connect(phase_b, native, label="./mvnw package -Dnative (container build, ~75s)", amber=True, lx=0, ly=-75),
]

bands = [
    {"x": 40, "y": 310, "w": 1090, "h": 190, "label": "Measured startup + RSS (same Postgres, profile=prod)", "fill": "#fafafa"},
]

col_build, col_start, col_rss = 70, 620, 850
row0, row_gap = 345, 32

rows = [
    ("Build", "Startup", "RSS", True),
    ("Phase A — JVM (spring-compat)", "1.492 s", "~316 MB", False),
    ("Phase B — JVM (idiomatic)", "1.431–1.437 s", "~304 MB", False),
    ("Phase B — native image", "0.048–0.049 s", "~73 MB", False),
]

notes = [
    {"x": W / 2, "y": 32, "text": "ch.15 — Review service: the two-phase migration template",
     "anchor": "middle", "bold": True, "size": 18},
    {"x": W / 2, "y": 60,
     "text": "Phase A de-risks the extraction fast; Phase B is where the \"why Quarkus\" case gets made with numbers.",
     "anchor": "middle", "size": 12, "color": "#555555"},
]
for i, (b, s, r, header) in enumerate(rows):
    y = row0 + i * row_gap
    bold = header
    color = "#1d1d1d" if header else "#333333"
    notes.append({"x": col_build, "y": y, "text": b, "anchor": "start", "bold": bold, "size": 12.5, "color": color})
    notes.append({"x": col_start, "y": y, "text": s, "anchor": "start", "bold": bold, "size": 12.5, "color": color})
    notes.append({"x": col_rss, "y": y, "text": r, "anchor": "start", "bold": bold, "size": 12.5, "color": color})

notes.append({"x": 40, "y": 480,
              "text": "~30x faster startup, ~4.2x less resident memory: JVM -> native, same idiomatic code "
                      "(closed-world reflection gotcha: ApiError needed @RegisterForReflection; see MIGRATION.md).",
              "anchor": "start", "bold": True, "size": 12, "color": "#2f5f3d"})

g.emit(
    "review-two-phase-migration", W, H,
    bands=bands,
    nodes=nodes,
    edges=edges,
    notes=notes,
)
