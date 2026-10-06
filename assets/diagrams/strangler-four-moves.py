#!/usr/bin/env python3
"""ch.14 figure: strangler-four-moves -- Fowler's strangler fig pattern as
four ordered engineering moves (intercept, route, incrementally replace,
retire), with the ordering itself as the point: the window in which the
cutover is still reversible spans moves 1-3, and closes the moment "retire"
happens. Retiring the old code before that window is supposed to close --
i.e. before a real verification like the chapter's stop-the-monolith
check, not just a green test suite -- is how an "incremental" migration
quietly becomes a big-bang rewrite with extra ceremony.

This is a companion to Figure 14.1 (strangler-review-extraction, the
before/after proxy topology for the Review cutover specifically). This
figure is deliberately topology-free: it is the four-move lifecycle in the
abstract, not a rendering of any one cutover's wiring.

Sourced from _docs/14-strangler-fig-pattern.md:
  - "Fowler's pattern, and why the metaphor fits" -- the four moves
    (intercept, route, incrementally replace, retire) and their exact
    wording, including "retiring too early is how 'incremental'
    migrations quietly turn into big-bang rewrites with extra ceremony."
  - "The reversibility window: a bug the equivalence suite almost missed"
    -- a green test suite is not proof of correct routing; the stop-the-
    monolith check is what actually proves it.
  - "Decommission: the one deliberate, irreversible step" -- the flag
    still works after decommission, but one of its two destinations is
    gone; GET /api/reviews -> 404, GET /api/orders -> 200, unaffected.
  - "What you learned" -- the four-moves-in-order summary bullet.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1400, 700

# ---- the reversible / irreversible bands -----------------------------------
band_reversible = {
    "x": 40, "y": 170, "w": 960, "h": 220,
    "label": "reversible -- the flag can move either direction (config, not code)",
    "fill": "#fafafa",
}
band_irreversible = {
    "x": 1000, "y": 170, "w": 320, "h": 220,
    "label": "irreversible once crossed",
    "fill": "#f4f4f4",
}

# ---- the four moves, left to right, in order -------------------------------
box1 = node(60, 220, 280, 110,
            ["1. Intercept -- ch.14", "proxy sees every request first"], style="box")
box2 = node(380, 220, 280, 110,
            ["2. Route", "flag decides which backend answers"], style="box")
box3 = node(700, 220, 280, 110,
            ["3. Incrementally replace", "one piece at a time,", "cutover verified before the next"],
            style="accent")
box4 = node(1020, 220, 280, 110,
            ["4. Retire", "old code removed last, not first"], style="ink")

nodes = [box1, box2, box3, box4]

edges = [
    connect(box1, box2, label="flag defaults to the old system"),
    connect(box2, box3, label="repeat -- one piece at a time"),
    connect(box3, box4, amber=True,
            label="verified first, not just tested", lx=0, ly=-145),
]

# ---- the warning: what happens if "retire" jumps the queue -----------------
ghost = node(460, 480, 560, 110,
             ["Retire before that check passes?",
              "the fallback is gone -- \"incremental\"",
              "quietly becomes a big-bang rewrite"],
             style="ghost")
nodes.append(ghost)
edges.append(connect(box3, ghost, dashed=True,
                      label="retire before the check passes ->"))

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.14 -- the strangler fig's four moves, in the order that matters",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "retiring out of order is how an \"incremental\" migration quietly becomes a big-bang rewrite",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": 60, "y": 375,
     "text": "both backends still exist here -- the flag can flip either way, no redeploy required",
     "anchor": "start", "size": 10.5, "color": "#555555"},

    {"x": 1020, "y": 352,
     "text": "the flag still works; the destination",
     "anchor": "start", "size": 10, "color": "#555555"},
    {"x": 1020, "y": 367,
     "text": "it used to select is gone (404)",
     "anchor": "start", "size": 10, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/14-strangler-fig-pattern.md (\"Fowler's pattern, and why the metaphor fits\"; "
             "\"The reversibility window\"; \"Decommission: the one deliberate, irreversible step\").",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "strangler-four-moves", W, H,
    bands=[band_reversible, band_irreversible],
    nodes=nodes,
    edges=edges,
    notes=notes,
)
