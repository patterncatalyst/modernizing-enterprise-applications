#!/usr/bin/env python3
"""ch.05 figure: adlc-tooling.

The agentic loop (8 steps) annotated with GENERIC tool roles at each step,
generalizing the deck's slides 3-5. This is a public repo: every vendor- or
codename-specific tool reference from the source deck is replaced with a
generic, industry-standard role label (hosted AI platform, local agent
runtime, local coding agent, internal developer platform (IDP), cloud dev
environment, issue tracker, coding assistant) — no product codenames are
reproduced here.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, octagon_positions, connect

g.OUT = os.path.dirname(__file__)

W, H = 980, 980
NW, NH = 210, 88

steps = [
    ["1. Express intent", "coding assistant (e.g. Claude Code)"],
    ["2. Agent understands", "local agent runtime"],
    ["3. Agent implements", "local coding agent"],
    ["4. Agent tests + docs", "local coding agent"],
    ["5. Human review", "human"],
    ["6. Deploy & ship", "internal developer platform (IDP)"],
    ["7. Monitoring & observability", "hosted AI platform"],
    ["8. Learn & iterate", "issue tracker"],
]

centers = octagon_positions(490, 500, 320, 320)
nodes = [node(cx - NW / 2, cy - NH / 2, NW, NH, lbl, style="accent")
         for (cx, cy), lbl in zip(centers, steps)]

edges = []
for i in range(8):
    j = (i + 1) % 8
    lbl = "cycle repeats" if i == 7 else None
    edges.append(connect(nodes[i], nodes[j], label=lbl))

notes = [
    {"x": 490, "y": 40, "text": "ADLC Tooling: Roles in the Agentic Loop",
     "anchor": "middle", "bold": True, "size": 20},
    {"x": 490, "y": 68, "text": "generic tool role shown under each step",
     "anchor": "middle", "size": 12, "color": "#555555"},
]

g.emit("adlc-tooling", W, H, nodes=nodes, edges=edges, notes=notes)
