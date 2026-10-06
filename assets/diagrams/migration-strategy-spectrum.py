#!/usr/bin/env python3
"""ch.04 figure: migration-strategy-spectrum -- four migration options
contrasted in one comparison figure (a legitimate single spectrum, not a
merge of unrelated diagrams): big-bang rewrite, the strangler fig (this
book's choice, highlighted), branch-by-abstraction, and parallel-run
(shadow / dual-write).

Big-bang rewrite and the strangler fig are grounded directly in
_docs/04-strategies-and-assessment.md: "Rebuild / Replace" / "Rip and
Rewrite" is the menu's highest-risk, discard-everything option, and the
chapter's own commitment is "incremental re-architecture, executed through
the Strangler Fig pattern, gated at every step by a behavior-equivalence
suite" -- defended against rebuilding on risk-shape grounds (Sam Newman,
*Building Microservices*, cited directly in the chapter). Branch-by-
abstraction and parallel-run are the other two incremental-migration
techniques from the same risk-reducing family the chapter's argument
belongs to (an in-process seam toggle, and a live comparison of two running
paths) -- included for contrast, not claimed as named in this chapter.

Sourced from: _docs/04-strategies-and-assessment.md ("The strategy menu:
what each option actually buys", "Why this book commits to incremental
strangler-fig re-architecting").
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node

g.OUT = os.path.dirname(__file__)

W, H = 1500, 560

COL_W, GUTTER = 330, 30
X0 = 40
Y0, COL_H = 150, 220

cols = [
    ("Big-bang rewrite", "box",
     ["risk taken at once: all of it", "time to value: one long cutover", "trade-off: cleanest end-state,", "no incremental verification"]),
    ("Strangler fig (this book)", "accent",
     ["risk taken at once: one seam", "time to value: continuous", "trade-off: equivalence-gated,", "slower end to end"]),
    ("Branch-by-abstraction", "box",
     ["risk taken at once: one branch", "time to value: fast per branch", "trade-off: good for in-process", "seams; data ownership unsolved"]),
    ("Parallel-run (shadow)", "box",
     ["risk taken at once: both paths", "time to value: needs a comparator", "trade-off: strong confidence,", "two live paths cost double"]),
]

nodes = []
for i, (title, style, lines) in enumerate(cols):
    x = X0 + i * (COL_W + GUTTER)
    nodes.append(node(x, Y0, COL_W, COL_H, [title] + lines, style=style))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.04 -- the migration-strategy spectrum: how much risk you take on at once, and how long you wait",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "this book commits to the strangler fig, gated at every step by a behavior-equivalence suite",
     "anchor": "middle", "size": 12, "color": "#2f5f3d"},

    {"x": X0 + 1 * (COL_W + GUTTER) + COL_W / 2, "y": Y0 - 14,
     "text": "this book's choice", "anchor": "middle", "bold": True, "size": 11.5, "color": "#2f5f3d"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/04-strategies-and-assessment.md (\"The strategy menu\", \"Why this book commits to "
             "incremental strangler-fig re-architecting\") -- Sam Newman's incremental-vs-rewrite risk-shape case, cited directly.",
     "anchor": "start", "size": 10.5, "color": "#555555"},
]

g.emit(
    "migration-strategy-spectrum", W, H,
    bands=[],
    nodes=nodes,
    edges=[],
    notes=notes,
)
