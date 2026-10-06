#!/usr/bin/env python3
"""ch.32 figure: over-decomposition-spectrum — the "how far do you split"
spectrum, from a single monolith to nanoservice sprawl, with a marker on where
this project deliberately stopped: six services aligned to six bounded contexts,
plus an aggregation gateway. The point is that more services is not the goal;
context-aligned services are, and past that line lies the distributed monolith.

Left to right: one monolith (too coupled) -> a modular monolith -> context-
aligned services (the right-sized band, where this project landed) -> finer-
grained services -> nanoservice sprawl / distributed monolith (too chatty).

Sourced from: the six contexts (ch.11-13), the final topology (ch.26), and the
over-decomposition discussion in ch.32.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1440, 520

# the "right-sized" band highlights the middle where this project landed
sweet = {"x": 470, "y": 110, "w": 520, "h": 300,
         "label": "right-sized: services aligned to bounded contexts", "fill": "#eaf4ec"}

mono = node(60, 170, 180, 150,
            ["1 monolith", "one deploy, one DB", "ch.08-10", "too coupled to change safely"], style="box")
modmono = node(270, 170, 170, 150,
               ["modular monolith", "modules, one deploy", "a valid destination", "— not this book's"], style="sub")
contexts = node(500, 170, 230, 170,
                ["6 context services", "order · inventory · payment", "shipping · notification · review",
                 "+ graphql-gateway", "<- this project (ch.26)"], style="accent")
finer = node(760, 170, 200, 170,
             ["finer-grained", "split a context further", "only when a real seam",
              "inside it demands it"], style="box")
nano = node(1010, 170, 190, 150,
            ["nanoservice sprawl", "a service per class", "distributed monolith:",
             "chatty, co-deployed, worse"], style="ghost")

nodes = [mono, modmono, contexts, finer, nano]

# a left-right spectrum arrow under everything
edges = [
    {"x1": 60, "y1": 440, "x2": 1200, "y2": 440, "amber": True,
     "label": "more, smaller services  ->"},
]

notes = [
    {"x": W / 2, "y": 40,
     "text": "ch.32 — the over-decomposition spectrum: where this project deliberately stopped",
     "anchor": "middle", "bold": True, "size": 16},
    {"x": W / 2, "y": 62,
     "text": "the goal is context-aligned services, not maximal splitting — past the sweet spot is the distributed monolith",
     "anchor": "middle", "size": 11, "color": "#555555"},
    {"x": 150, "y": 470, "text": "too coupled", "anchor": "middle", "size": 11, "color": "#777777"},
    {"x": 1105, "y": 470, "text": "too chatty", "anchor": "middle", "size": 11, "color": "#777777"},
    {"x": W / 2, "y": H - 16,
     "text": "Six services came from six bounded contexts found by event storming (ch.11-13) — the seams were discovered, not invented to hit a count.",
     "anchor": "middle", "size": 10, "color": "#777777"},
]

g.emit("over-decomposition-spectrum", W, H, bands=[sweet], nodes=nodes, edges=edges, notes=notes)
