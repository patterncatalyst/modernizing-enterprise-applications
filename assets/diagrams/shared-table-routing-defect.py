#!/usr/bin/env python3
"""ch.07 figure: shared-table-routing-defect — the chapter's pivotal proof
moment in "The ADLC Safety Net" (_docs/07-adlc-safety-net.md, "Operate:
flipping the flag — and catching bug #2"). A legitimate [SIDE] two-panel
figure: the left panel states the ambiguity a black-box suite cannot resolve,
the right panel shows the one check that resolves it.

LEFT panel — the ambiguity: the Camel strangler proxy (:8888) can route an
`/api/reviews` request to either the Spring monolith (:8080) or the Quarkus
review-service (:8081). Because Review's Phase A form was scoped to keep
sharing the monolith's `public` schema (the one extracted service that did
not get its own schema this chapter — see the chapter's "Map" section), both
backends read and write the exact same `reviews` table. A behavior-
equivalence suite only ever sees the HTTP response — status code, body shape
— so a misrouted request that quietly falls through to the monolith and a
correctly routed request that reaches review-service return identical
bodies, because for this slice of the walking skeleton both statements
describe the same rows. 49/49 green proved nothing about which path the
request actually took.

RIGHT panel — the differential test that resolved it: stop the monolith
outright, so there is no longer any process on the other side of a misrouted
request, and re-try the same route through the proxy. Only the service the
proxy's decision actually reaches can still answer. This is the exact check
`examples/01-strangler-proxy/CUTOVER.md` (timeline step 2) ran: with the flag
on and the monolith down, `GET /api/reviews` returns 200 served by
review-service while `GET /api/orders` still 500s against the dead monolith
(expected — that route isn't cut yet), proving the proxy's routing decision,
not just its response, was correct.

Sourced from `_docs/07-adlc-safety-net.md` ("Map," "Operate: flipping the
flag — and catching bug #2") and `examples/01-strangler-proxy/CUTOVER.md`
(timeline step 2, the differential monolith-down test, post-fix result). No
codenames; generic/public names only.
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "scripts"))
sys.path.insert(0, os.path.dirname(__file__))
import generate_diagram as g
from _lib import node, connect

g.OUT = os.path.dirname(__file__)

W, H = 1440, 780

LEFT_X, RIGHT_X, PANEL_W = 20, 740, 680

left_band = {"x": LEFT_X, "y": 90, "w": PANEL_W, "h": 600,
             "label": "The ambiguity — a black-box suite sees the same response either way",
             "fill": "#fafafa"}
right_band = {"x": RIGHT_X, "y": 90, "w": PANEL_W, "h": 600,
              "label": "The differential test — remove one answer, then ask again",
              "fill": "#eaf4ec"}

# ============================================================================
# LEFT — both backends share the same reviews table; the suite can't tell
# ============================================================================
suite_l = node(LEFT_X + 40, 150, 600, 70,
                ["Behavior-equivalence suite", "HTTP only — status code, body shape"], style="sub")
proxy_l = node(LEFT_X + 40, 250, 600, 70,
               ["Camel strangler proxy :8888", "flag: strangler.review.enabled"], style="accent")
mono_l = node(LEFT_X + 40, 360, 280, 90,
              ["Spring monolith :8080", "answers /api/reviews (path A)"], style="box")
rev_l = node(LEFT_X + 360, 360, 280, 90,
             ["Review service (Quarkus) :8081", "answers /api/reviews (path B)"], style="box")
pg_l = node(LEFT_X + 120, 520, 440, 90,
            ["public schema", "reviews table — read & written by both"], style="ink")

left_nodes = [suite_l, proxy_l, mono_l, rev_l, pg_l]
left_edges = [
    connect(suite_l, proxy_l, label="HTTP request only"),
    connect(proxy_l, mono_l, label="path A"),
    connect(proxy_l, rev_l, label="path B"),
    connect(mono_l, pg_l, amber=True),
    connect(rev_l, pg_l, amber=True, label="same rows either way"),
]

# ============================================================================
# RIGHT — stop the monolith; only one backend can still answer
# ============================================================================
proxy_r = node(RIGHT_X + 40, 250, 600, 70,
               ["Camel strangler proxy :8888", "flag: strangler.review.enabled = true"], style="accent")
mono_r = node(RIGHT_X + 40, 360, 280, 90,
              ["Spring monolith :8080", "STOPPED — no process to answer"], style="ghost")
rev_r = node(RIGHT_X + 360, 360, 280, 90,
             ["Review service (Quarkus) :8081", "the only process left standing"], style="accent")
result1 = node(RIGHT_X + 40, 500, 280, 90,
               ["GET /api/reviews -> 200", "served by review-service"], style="ink")
result2 = node(RIGHT_X + 360, 500, 280, 90,
               ["GET /api/orders -> 500", "still targets the dead monolith (expected)"], style="ink")

right_nodes = [proxy_r, mono_r, rev_r, result1, result2]
right_edges = [
    connect(proxy_r, mono_r, dashed=True, label="no process to receive it"),
    connect(proxy_r, rev_r, amber=True, label="only path that can still answer"),
    connect(mono_r, result2, dashed=True),
    connect(rev_r, result1),
]

notes = [
    {"x": W / 2, "y": 32,
     "text": "ch.07 — the shared-table proof: why 49/49 green couldn't tell path A from path B",
     "anchor": "middle", "bold": True, "size": 17},
    {"x": W / 2, "y": 56,
     "text": "Review's Phase A form still shares the monolith's public schema — the one extraction that did not get its own schema yet",
     "anchor": "middle", "size": 12, "color": "#555555"},

    {"x": LEFT_X + 40, "y": 630,
     "text": "A misrouted request and a correctly routed one return identical bodies, because both read and write the same reviews rows.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": RIGHT_X + 40, "y": 630,
     "text": "With the monolith down, a 200 from review-service proves the proxy's decision reached it —",
     "anchor": "start", "size": 11, "color": "#555555"},
    {"x": RIGHT_X + 40, "y": 646,
     "text": "not just that two backends happened to agree.",
     "anchor": "start", "size": 11, "color": "#555555"},

    {"x": 40, "y": H - 16,
     "text": "Sourced from _docs/07-adlc-safety-net.md (\"Map\"; \"Operate: flipping the flag — and catching bug #2\") and examples/01-strangler-proxy/CUTOVER.md (timeline step 2, post-fix).",
     "anchor": "start", "size": 10, "color": "#777777"},
]

g.emit(
    "shared-table-routing-defect", W, H,
    bands=[left_band, right_band],
    nodes=left_nodes + right_nodes,
    edges=left_edges + right_edges,
    notes=notes,
)
