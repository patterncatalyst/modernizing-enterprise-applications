#!/usr/bin/env python3
"""ch.06 figure: structural-vs-behavioral-gates — "MCP tools: acting against
the real toolchain, not from memory," the paragraph beginning "That last
parenthetical is worth lingering on": the two gate kinds this chapter
contrasts, and which class of mistake each one actually catches.

TOP — the STRUCTURAL gate: camel-mcp's `camel_validate_route` /
`camel_configuration_validate`, run during Generate, before a route is ever
deployed. It "confirms the route compiles into a legal Camel processing
graph" — catching "a malformed choice()/when()/otherwise() block, a
reference to a component that was never added as a dependency, an endpoint
URI with a typo'd parameter." It is explicitly "not, and was never meant to
be, a behavioral gate — it has no opinion about whether a routing predicate
matches the right requests once real traffic flows through it." Camel MCP
"catches the class of defect that would otherwise surface as a confusing
runtime stack trace."

BOTTOM — the BEHAVIORAL gate: the Newman behavior-equivalence suite and its
Opus adversarial follow-up, run during Verify against a running service.
"The equivalence gate and its adversarial follow-up catch the class of
defect that surfaces as a misleadingly correct-looking response" — exactly
"the gap the differential monolith-down test in Chapter 7 existed to
close."

"Neither one substitutes for the other, and understanding which layer is
responsible for which kind of mistake is most of what it takes to trust a
loop this fast."

Sourced from `_docs/06-agents-skills-mcp.md` ("MCP tools: acting against the
real toolchain, not from memory," the `camel_validate_route` transcript, and
the paragraph on what each layer catches). No codenames; generic/public
names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 760

# ============================================================================
# TOP band — the structural gate
# ============================================================================
band_top = {"x": 20, "y": 80, "w": 1360, "h": 250,
            "label": "Structural gate — camel-mcp route validation (Generate phase, before deploy)",
            "fill": "#fafafa"}

route_check = node(60, 150, 400, 120,
                    ["camel_validate_route /", "camel_configuration_validate",
                     "checks the route compiles -> legal Camel graph"], style="accent")
catches1 = node(500, 150, 380, 120,
                ["Catches", "malformed choice/when/otherwise,",
                 "unresolved refs, typo'd endpoint params"], style="box")
miss1 = node(920, 150, 420, 120,
             ["Cannot catch", "whether a predicate matches the right",
              "requests once real traffic flows"], style="ink")

top_nodes = [route_check, catches1, miss1]
top_edges = [
    connect(route_check, catches1, amber=True, label="defect surfaces as: a confusing runtime stack trace", ly=-80),
    connect(catches1, miss1, dashed=True, label="gap this layer leaves open", ly=-80),
]

# ============================================================================
# BOTTOM band — the behavioral gate
# ============================================================================
band_bottom = {"x": 20, "y": 390, "w": 1360, "h": 280,
               "label": "Behavioral gate — equivalence suite + adversarial follow-up (Verify phase, against a running service)",
               "fill": "#eaf4ec"}

suite_check = node(60, 460, 400, 120,
                    ["Newman equivalence suite +", "Opus adversarial follow-up",
                     "exercises real requests, running service"], style="accent")
catches2 = node(500, 460, 420, 120,
                ["Catches", "a routing predicate matching the wrong",
                 "requests; a misleadingly correct response"], style="box")

bottom_nodes = [suite_check, catches2]

# the cross-band connector routes as an L (like the bus-lane pattern) so it
# lands on suite_check's top-center, clear of the amber label sitting above
# suite_check's left side.
GAP_Y = 360
miss1_cx = miss1["x"] + miss1["w"] / 2
suite_cx = suite_check["x"] + suite_check["w"] / 2
miss1_bottom = miss1["y"] + miss1["h"]
suite_top = suite_check["y"]

bottom_edges = [
    connect(suite_check, catches2, amber=True, label="defect surfaces as: a misleadingly correct-looking response", ly=-75),
    {"x1": miss1_cx, "y1": miss1_bottom, "x2": miss1_cx, "y2": GAP_Y, "dashed": True},
    {"x1": miss1_cx, "y1": GAP_Y, "x2": suite_cx, "y2": GAP_Y, "dashed": True,
     "label": "the gap the behavioral gate exists to close"},
    {"x1": suite_cx, "y1": GAP_Y, "x2": suite_cx, "y2": suite_top, "dashed": True},
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.06 — structural gate vs behavioral gate: two layers, two different classes of mistake",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "neither substitutes for the other — knowing which layer is responsible for which kind of defect is most of what it takes to trust a loop this fast",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 980, "y": 520,
     "text": "ch.07's differential monolith-down test sharpens this gate further",
     "anchor": "start", "size": 10, "color": "#777777"},

    {"x": 40, "y": H - 46,
     "text": "Sourced from _docs/06-agents-skills-mcp.md (\"MCP tools: acting against the real toolchain, not from memory\").",
     "anchor": "start", "size": 10.5, "color": "#555555"},
    {"x": 40, "y": H - 16,
     "text": "Quoted: the camel_validate_route transcript, and the paragraph naming what each layer catches.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "structural-vs-behavioral-gates", W, H,
    bands=[band_top, band_bottom],
    nodes=top_nodes + bottom_nodes,
    edges=top_edges + bottom_edges,
    notes=notes,
)
